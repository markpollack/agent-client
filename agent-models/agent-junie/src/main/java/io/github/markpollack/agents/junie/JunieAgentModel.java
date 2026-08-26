/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.junie.JuniePhaseCapture;
import io.github.markpollack.journal.junie.JunieSessionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter from the agent-client SPI to the JetBrains Junie CLI, driven over the Agent
 * Client Protocol.
 *
 * <h2>Junie is not wrapped in a CLI SDK</h2>
 *
 * <p>
 * Every other provider in this project shells out to a CLI and parses its stdout, which
 * is why each has a {@code provider-sdks} module. Junie speaks ACP, so the published ACP
 * client is the transport and no bespoke SDK is warranted — the same call this project
 * already makes for Claude, which uses the external {@code claude-code-sdk}.
 *
 * <h2>The ACP code is deliberately clustered, not distributed</h2>
 *
 * <p>
 * All protocol knowledge lives in this one class, in {@link #createAcpClient},
 * {@link #handleAcpUpdate} and {@link #executePrompt}. Nothing ACP-shaped leaks into
 * agent-client's core, and there is intentionally no generic {@code AcpAgentModel},
 * {@code AcpProvider} or capability-normalization layer. Junie is the first ACP provider
 * here; a second one is what should supply the evidence for extracting a common layer,
 * rather than that layer being designed in advance from a single example.
 *
 * <h2>Locating the native trajectory</h2>
 *
 * <p>
 * Junie writes a durable {@code events.jsonl} per session. The session directory name is
 * verified to be exactly the session id returned by ACP {@code session/new}, so the trace
 * is resolved directly rather than by guessing at the most recently modified directory.
 * See {@link #locateEventsFile}.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class JunieAgentModel implements AgentModel {

	private static final Logger logger = LoggerFactory.getLogger(JunieAgentModel.class);

	private static final String DEFAULT_COMMAND = "junie";

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

	private final String command;

	private final JunieAgentOptions defaultOptions;

	private final Path sessionsDirectory;

	private final boolean captureEnabled;

	public JunieAgentModel(String command, JunieAgentOptions defaultOptions, Path sessionsDirectory,
			boolean captureEnabled) {
		this.command = (command != null && !command.isBlank()) ? command : DEFAULT_COMMAND;
		this.defaultOptions = (defaultOptions != null) ? defaultOptions : JunieAgentOptions.builder().build();
		this.sessionsDirectory = (sessionsDirectory != null) ? sessionsDirectory : defaultSessionsDirectory();
		this.captureEnabled = captureEnabled;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		JunieAgentOptions options = mergeOptions(request);

		String goal = request.goal();
		if (options.getSystemInstructions() != null && !options.getSystemInstructions().isEmpty()) {
			// Junie has no system-prompt flag, so portable systemInstructions are
			// prepended, matching how agent-antigravity handles the same gap.
			goal = options.getSystemInstructions() + "\n\n" + goal;
		}

		Path workingDirectory = resolveWorkingDirectory(request, options);
		logger.info("Executing Junie over ACP in {} with a goal of {} characters", workingDirectory, goal.length());

		long startedAt = System.nanoTime();
		try {
			JunieAcpRun run = executePrompt(goal, workingDirectory, options);
			Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
			return toAgentResponse(run, duration, options);
		}
		catch (Exception ex) {
			logger.warn("Junie agent execution failed: {}", ex.getMessage());
			return toErrorResponse(ex);
		}
	}

	@Override
	public boolean isAvailable() {
		try {
			Process process = new ProcessBuilder(this.command, "--version").redirectErrorStream(true).start();
			boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
			if (!exited) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception ex) {
			logger.warn("Junie CLI availability check failed: {}", ex.getMessage());
			return false;
		}
	}

	// ---------------------------------------------------------------------
	// ACP — everything below this line is protocol-specific and stays here.
	// ---------------------------------------------------------------------

	/**
	 * Build the stdio-launched ACP client for {@code junie --acp true}.
	 *
	 * <p>
	 * Neutral options map onto Junie's own flags — {@code --model}, {@code --effort},
	 * {@code --project} — and every {@link JunieAgentOptions#getExtras() extra} is
	 * appended verbatim as {@code --key value}, so a new Junie flag never requires an
	 * edit here.
	 */
	private AcpSyncClient createAcpClient(Path workingDirectory, JunieAgentOptions options,
			java.util.function.Consumer<AcpSchema.SessionNotification> updateConsumer) {

		AgentParameters.Builder params = AgentParameters.builder(this.command)
			.args(buildLaunchArgs(workingDirectory, options));
		params.env(options.getEnvironmentVariables());

		AcpSchema.ClientCapabilities capabilities = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(true, true), true);

		return AcpClient.sync(new StdioAcpClientTransport(params.build()))
			.requestTimeout(options.getTimeout() != null ? options.getTimeout() : DEFAULT_TIMEOUT)
			.clientCapabilities(capabilities)
			.sessionUpdateConsumer(updateConsumer)
			.build();
	}

	/**
	 * The {@code junie} command line for one run.
	 *
	 * <p>
	 * Package-private so the passthrough contract is directly testable: neutral options
	 * become Junie's own flags, and every extra becomes {@code --key value}, with a
	 * {@code true} boolean becoming a bare flag and {@code false} omitted entirely.
	 */
	static List<String> buildLaunchArgs(Path workingDirectory, JunieAgentOptions options) {
		List<String> args = new ArrayList<>(
				List.of("--acp", "true", "--project", workingDirectory.toString()));

		if (options.getModel() != null) {
			args.add("--model");
			args.add(options.getModel());
		}
		if (options.getEffort() != null) {
			args.add("--effort");
			args.add(options.getEffort());
		}
		for (Map.Entry<String, Object> extra : options.getExtras().entrySet()) {
			Object value = extra.getValue();
			if (value instanceof Boolean flag) {
				if (flag) {
					args.add("--" + extra.getKey());
				}
				continue;
			}
			if (value != null) {
				args.add("--" + extra.getKey());
				args.add(String.valueOf(value));
			}
		}
		return args;
	}

	/**
	 * Fold one ACP session update into the accumulating run.
	 *
	 * <p>
	 * Only what agent-client actually needs is consumed: assistant text becomes the
	 * answer, thoughts and tool calls are counted for the response metadata. Unknown
	 * update kinds are ignored rather than rejected — Junie is free to add them, and an
	 * adapter that threw on an unrecognized notification would break on a CLI update.
	 */
	private void handleAcpUpdate(AcpSchema.SessionNotification notification, StringBuilder answer,
			List<String> toolTitles, AtomicInteger thoughtCount) {
		AcpSchema.SessionUpdate update = notification.update();
		if (update instanceof AcpSchema.AgentMessageChunk chunk) {
			appendText(answer, chunk.content());
		}
		else if (update instanceof AcpSchema.AgentThoughtChunk) {
			thoughtCount.incrementAndGet();
		}
		else if (update instanceof AcpSchema.ToolCall toolCall) {
			toolTitles.add((toolCall.title() != null) ? toolCall.title() : String.valueOf(toolCall.kind()));
		}
	}

	private void appendText(StringBuilder answer, AcpSchema.ContentBlock content) {
		if (content instanceof AcpSchema.TextContent text && text.text() != null) {
			answer.append(text.text());
		}
	}

	/**
	 * Run the full ACP lifecycle: initialize, session/new, session/prompt, close.
	 */
	private JunieAcpRun executePrompt(String goal, Path workingDirectory, JunieAgentOptions options) {
		StringBuilder answer = new StringBuilder();
		List<String> toolTitles = new CopyOnWriteArrayList<>();
		AtomicInteger thoughtCount = new AtomicInteger();

		AcpSyncClient client = createAcpClient(workingDirectory, options,
				notification -> handleAcpUpdate(notification, answer, toolTitles, thoughtCount));
		try {
			AcpSchema.InitializeResponse initialize = client.initialize();
			logger.debug("Junie ACP initialize: protocolVersion={} agentInfo={}", initialize.protocolVersion(),
					initialize.agentInfo());

			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest(workingDirectory.toString(), List.of()));
			String sessionId = session.sessionId();
			logger.info("Junie ACP session {}", sessionId);

			AcpSchema.PromptResponse prompt = client.prompt(
					new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(goal))));

			return new JunieAcpRun(sessionId, goal, answer.toString(), prompt.stopReason(), List.copyOf(toolTitles),
					thoughtCount.get(), initialize.agentInfo(), locateEventsFile(sessionId));
		}
		finally {
			if (!client.closeGracefully()) {
				client.close();
			}
		}
	}

	/**
	 * Resolve the native trajectory for an ACP session.
	 *
	 * <p>
	 * Junie names each session directory with exactly the id it hands back from
	 * {@code session/new} — verified against a live 26.8.24 (2929.5) run by snapshotting
	 * the sessions directory before the call and diffing it after. That correspondence is
	 * what makes this a lookup instead of a search, so it is worth re-checking if a Junie
	 * upgrade ever changes the session id format.
	 *
	 * <h3>The trace is secret-bearing — do not copy it</h3>
	 *
	 * <p>
	 * Junie writes the launching process's entire environment into {@code events.jsonl},
	 * unredacted and repeatedly, via its {@code EnvironmentVariablesUpdatedEvent}. A
	 * single observed run contained 103 variables including live API keys. Only the
	 * <em>path</em> is published on {@code providerFields}, and the capture parser
	 * discards that event rather than recording it.
	 *
	 * <p>
	 * This is why there is no trace-archival step here of the kind the Claude adapter
	 * has: archiving a Junie trajectory would copy live credentials out of the user's
	 * home directory into a run directory. Do not add one without redacting that event
	 * first.
	 *
	 * <p>
	 * Junie-specific session discovery. Extract only if a second ACP provider turns out
	 * to need equivalent behavior.
	 */
	private Path locateEventsFile(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return null;
		}
		Path events = this.sessionsDirectory.resolve(sessionId).resolve("events.jsonl");
		if (!Files.isRegularFile(events)) {
			logger.debug("No Junie events.jsonl at {}", events);
			return null;
		}
		return events;
	}

	private static Path defaultSessionsDirectory() {
		return Paths.get(System.getProperty("user.home"), ".junie", "sessions");
	}

	/**
	 * What one ACP run produced. Package-private and intentionally not part of the public
	 * surface.
	 */
	private record JunieAcpRun(String sessionId, String promptText, String answer, AcpSchema.StopReason stopReason,
			List<String> toolTitles, int thoughtCount, AcpSchema.Implementation agentInfo, Path eventsFile) {
	}

	// ---------------------------------------------------------------------
	// Response mapping
	// ---------------------------------------------------------------------

	private AgentResponse toAgentResponse(JunieAcpRun run, Duration duration, JunieAgentOptions options) {
		boolean successful = run.stopReason() == AcpSchema.StopReason.END_TURN;
		String finishReason = successful ? "SUCCESS" : String.valueOf(run.stopReason());

		Map<String, Object> providerFields = new LinkedHashMap<>();
		providerFields.put("successful", successful);
		providerFields.put("stopReason", String.valueOf(run.stopReason()));
		providerFields.put("sessionId", run.sessionId());
		providerFields.put("toolCallCount", run.toolTitles().size());
		providerFields.put("toolCallTitles", run.toolTitles());
		providerFields.put("thoughtChunkCount", run.thoughtCount());
		if (run.agentInfo() != null) {
			providerFields.put("agentName", run.agentInfo().name());
			providerFields.put("agentVersion", run.agentInfo().version());
		}
		if (run.eventsFile() != null) {
			providerFields.put("eventsPath", run.eventsFile().toString());
		}

		attachPhaseCapture(providerFields, run);

		AgentGeneration generation = new AgentGeneration(run.answer(),
				new AgentGenerationMetadata(finishReason, Map.copyOf(providerFields)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model((options.getModel() != null) ? options.getModel() : "junie-default")
			.duration(duration)
			.sessionId((run.sessionId() != null) ? run.sessionId() : "")
			.providerFields(Map.copyOf(providerFields))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	/**
	 * Parse Junie's native {@code events.jsonl} into a phase capture and publish it on
	 * {@code providerFields["phaseCapture"]}, which is what
	 * {@code AgentClientResponse.getPhaseCapture()} reads.
	 *
	 * <p>
	 * Capture is on by default and opt-out. A provider that runs fine and silently
	 * produces no journal is the failure mode this project has already shipped eight
	 * times; joining the two libraries belongs here, not in every consumer.
	 */
	private void attachPhaseCapture(Map<String, Object> providerFields, JunieAcpRun run) {
		if (!this.captureEnabled || run.eventsFile() == null) {
			return;
		}
		try {
			providerFields.put("phaseCapture", parseCapture(run));
		}
		catch (IOException ex) {
			// A trajectory that will not parse must not fail a run that succeeded.
			logger.warn("Junie phase capture failed for session {}: {}", run.sessionId(), ex.getMessage());
		}
	}

	private JuniePhaseCapture parseCapture(JunieAcpRun run) throws IOException {
		return JunieSessionParser.parse(run.eventsFile(), "junie-acp", run.promptText());
	}

	// ---------------------------------------------------------------------
	// Options
	// ---------------------------------------------------------------------

	private JunieAgentOptions mergeOptions(AgentTaskRequest request) {
		if (request.options() instanceof JunieAgentOptions junieOptions) {
			return junieOptions;
		}

		JunieAgentOptions.Builder builder = JunieAgentOptions.builder()
			.model(this.defaultOptions.getModel())
			.effort(this.defaultOptions.getEffort())
			.timeout(this.defaultOptions.getTimeout())
			.workingDirectory(this.defaultOptions.getWorkingDirectory())
			.environmentVariables(this.defaultOptions.getEnvironmentVariables())
			.maxTurns(this.defaultOptions.getMaxTurns())
			.systemInstructions(this.defaultOptions.getSystemInstructions())
			.jsonSchema(this.defaultOptions.getJsonSchema())
			.executablePath(this.defaultOptions.getExecutablePath())
			.extras(this.defaultOptions.getExtras());

		// Portable fallbacks — the path a cross-provider caller takes.
		if (request.options() != null) {
			if (request.options().getModel() != null) {
				builder.model(request.options().getModel());
			}
			// Portable low/medium/high are exactly Junie's --effort values.
			if (request.options().getEffort() != null) {
				builder.effort(request.options().getEffort());
			}
			if (request.options().getTimeout() != null) {
				builder.timeout(request.options().getTimeout());
			}
			if (request.options().getMaxTurns() != null) {
				builder.maxTurns(request.options().getMaxTurns());
			}
			if (request.options().getSystemInstructions() != null) {
				builder.systemInstructions(request.options().getSystemInstructions());
			}
			builder.extras(request.options().getExtras());
		}
		return builder.build();
	}

	private Path resolveWorkingDirectory(AgentTaskRequest request, JunieAgentOptions options) {
		if (request.workingDirectory() != null) {
			return request.workingDirectory();
		}
		if (options.getWorkingDirectory() != null) {
			return Paths.get(options.getWorkingDirectory());
		}
		return Paths.get(System.getProperty("user.dir"));
	}

	private AgentResponse toErrorResponse(Exception ex) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName();
		AgentGeneration generation = new AgentGeneration(message,
				new AgentGenerationMetadata("ERROR", Map.of("error", message)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model("junie-default")
			.duration(Duration.ZERO)
			.providerFields(Map.of("error", message, "successful", false))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	public static final class Builder {

		private String command;

		private JunieAgentOptions defaultOptions;

		private Path sessionsDirectory;

		private boolean captureEnabled = true;

		private Builder() {
		}

		public Builder command(String command) {
			this.command = command;
			return this;
		}

		public Builder defaultOptions(JunieAgentOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Where Junie keeps its session directories. Defaults to
		 * {@code ~/.junie/sessions}; override for a CLI configured with a non-default
		 * home, or in tests.
		 */
		public Builder sessionsDirectory(Path sessionsDirectory) {
			this.sessionsDirectory = sessionsDirectory;
			return this;
		}

		/**
		 * Trajectory capture is enabled by default; this is the opt-out.
		 */
		public Builder captureEnabled(boolean captureEnabled) {
			this.captureEnabled = captureEnabled;
			return this;
		}

		public JunieAgentModel build() {
			return new JunieAgentModel(this.command, this.defaultOptions, this.sessionsDirectory, this.captureEnabled);
		}

	}

}
