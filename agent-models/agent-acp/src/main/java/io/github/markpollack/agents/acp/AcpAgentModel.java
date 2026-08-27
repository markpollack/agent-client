/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link AgentApi} adapter for every CLI that speaks the Agent Client Protocol,
 * specialised per agent by an {@link AcpAgentProfile}.
 *
 * <h2>Extracted from two, not designed from one</h2>
 *
 * <p>
 * The Junie adapter deliberately kept all its ACP knowledge in one class and built no
 * generic layer, on the grounds that a second implementation should supply the evidence
 * for what is actually common. This class is that extraction. Driving Junie 26.8.24 and
 * Grok 1.0.5 through the same protocol code showed the lifecycle, the update stream and
 * the tool-call shape to be genuinely shared, and five other things not to be — which is
 * exactly the list on {@link AcpAgentProfile}.
 *
 * <h2>The two planes, measured rather than assumed</h2>
 *
 * <p>
 * ACP is the control plane; the agent's own trajectory file is the research record. Both
 * are kept, permanently, and the same task run through Grok over ACP and over its native
 * stdout says why. The live ACP stream carries the answer, the thinking, the tool calls
 * with their inputs, outputs and touched paths, and — for Grok — the whole token and cost
 * vector on the prompt response {@code _meta}. What it does not carry, and what only the
 * durable trajectory holds, is per-tool latency, time-to-first-token, and the permission
 * decisions with the time a human spent on them. None of that is recoverable from the
 * protocol, and a trajectory assembled from ACP alone would report those runs as
 * indistinguishable.
 *
 * <h2>An SDK gap degrades here, it does not fail here</h2>
 *
 * <p>
 * {@code session/update} is handled through a raw notification handler rather than the
 * SDK's typed {@code sessionUpdateConsumer}, so that an update kind this SDK version does
 * not know is counted and carried on the response instead of throwing inside the
 * notification pipeline. That is not hypothetical: {@code acp-core} 0.16.1 types ten
 * update kinds, and both agents measured here emit an eleventh.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class AcpAgentModel implements AgentApi {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentModel.class);

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

	private final AcpAgentProfile profile;

	private final String command;

	private final AgentOptions defaultOptions;

	private final boolean captureEnabled;

	public AcpAgentModel(AcpAgentProfile profile, String command, AgentOptions defaultOptions, boolean captureEnabled) {
		if (profile == null) {
			throw new IllegalArgumentException("An AcpAgentProfile is required");
		}
		this.profile = profile;
		this.command = (command != null && !command.isBlank()) ? command : profile.defaultCommand();
		this.defaultOptions = defaultOptions;
		this.captureEnabled = captureEnabled;
	}

	public static Builder builder(AcpAgentProfile profile) {
		return new Builder(profile);
	}

	/**
	 * The profile driving this model, for adapters that wrap it.
	 * @return the profile
	 */
	public AcpAgentProfile profile() {
		return this.profile;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		AgentOptions options = AcpMergedOptions.merge(request.options(), this.defaultOptions);
		Path workingDirectory = resolveWorkingDirectory(request, options);
		String goal = prepareGoal(request, options);

		logger.info("Executing {} over ACP in {} with a goal of {} characters", this.profile.providerKey(),
				workingDirectory, goal.length());

		Instant startedAt = Instant.now();
		try {
			AcpRunRecord run = executePrompt(goal, workingDirectory, options, startedAt);
			return toAgentResponse(run, options);
		}
		catch (Exception ex) {
			logger.warn("{} agent execution failed: {}", this.profile.providerKey(), ex.getMessage());
			return toErrorResponse(ex);
		}
	}

	@Override
	public boolean isAvailable() {
		try {
			Process process = new ProcessBuilder(this.command, "--version").redirectErrorStream(true).start();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
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
			logger.warn("{} CLI availability check failed: {}", this.profile.providerKey(), ex.getMessage());
			return false;
		}
	}

	// ---------------------------------------------------------------------
	// ACP lifecycle
	// ---------------------------------------------------------------------

	private AcpRunRecord executePrompt(String goal, Path workingDirectory, AgentOptions options, Instant startedAt) {
		AcpUpdateFold fold = new AcpUpdateFold();
		AcpSyncClient client = createClient(workingDirectory, options, fold);
		try {
			AcpSchema.InitializeResponse initialize = client.initialize();
			logger.debug("{} ACP initialize: protocolVersion={} agentInfo={}", this.profile.providerKey(),
					initialize.protocolVersion(), initialize.agentInfo());

			String authMethodId = this.profile.authMethodId(initialize);
			if (authMethodId != null) {
				logger.debug("Authenticating {} with method {}", this.profile.providerKey(), authMethodId);
				client.authenticate(new AcpSchema.AuthenticateRequest(authMethodId));
			}

			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest(workingDirectory.toString(), List.of(), null, null));
			String sessionId = session.sessionId();
			logger.info("{} ACP session {}", this.profile.providerKey(), sessionId);

			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(textContent(goal))));

			Duration duration = Duration.between(startedAt, Instant.now());
			Path trajectory = locateTrajectory(sessionId, workingDirectory, startedAt);
			return new AcpRunRecord(sessionId, goal, fold.answer(), fold.thinking(),
					String.valueOf(prompt.stopReason()), nameSteps(fold), fold.thoughtChunkCount(),
					fold.messageChunkCount(), fold.unknownUpdateKinds(), agentName(initialize),
					agentVersion(initialize), prompt.meta(), trajectory, duration);
		}
		finally {
			if (!client.closeGracefully()) {
				client.close();
			}
		}
	}

	/**
	 * Build the stdio-launched ACP client.
	 *
	 * <p>
	 * File-system and permission handlers are always registered, not only when a caller
	 * asks for them. Declaring {@code fs} capability without serving the requests is a
	 * latent bug rather than a saving: Grok really does call {@code fs/read_text_file}
	 * and {@code fs/write_text_file} during ordinary work, and an unserved request comes
	 * back as JSON-RPC {@code -32601} to an agent that was told the client could do it.
	 */
	private AcpSyncClient createClient(Path workingDirectory, AgentOptions options, AcpUpdateFold fold) {
		AgentParameters.Builder params = AgentParameters.builder(this.command)
			.args(this.profile.launchArgs(workingDirectory, options));
		params.env(this.profile.environment(options));

		AcpSchema.ClientCapabilities capabilities = new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(true, true), true);

		return AcpClient.sync(new StdioAcpClientTransport(params.build()))
			.requestTimeout((options != null && options.getTimeout() != null) ? options.getTimeout() : DEFAULT_TIMEOUT)
			.clientCapabilities(capabilities)
			.readTextFileHandler(AcpAgentModel::readTextFile)
			.writeTextFileHandler(AcpAgentModel::writeTextFile)
			.requestPermissionHandler(AcpAgentModel::approve)
			.notificationHandler(AcpSchema.METHOD_SESSION_UPDATE, notificationParams -> {
				acceptUpdate(fold, notificationParams);
				return Mono.empty();
			})
			.build();
	}

	/**
	 * A text content block whose discriminator is written exactly once.
	 *
	 * <p>
	 * {@code acp-core} 0.16.1 declares {@code type} both as Jackson's type-info property
	 * on {@code ContentBlock} and as an explicit field on {@code TextContent}, and its
	 * one-argument constructor sets the field. The result on the wire is
	 * {@code {"type":"text","type":"text","text":"..."}} — a duplicate key in every
	 * {@code session/prompt} the SDK sends.
	 *
	 * <p>
	 * Jackson-based agents accept that silently, last key winning, which is why Junie has
	 * never complained. Strict parsers do not: Grok is Rust and answers
	 * {@code -32602 Invalid params: duplicate field `type`}, failing the prompt outright.
	 * Leaving the field null lets the type-info property supply it alone, which is both
	 * correct on the wire and still readable by the SDK itself.
	 *
	 * <p>
	 * This is a workaround for a defect in a sibling library, not a design choice here.
	 * It should be deleted when {@code acp-core} stops writing the discriminator twice.
	 */
	private static AcpSchema.TextContent textContent(String text) {
		return new AcpSchema.TextContent(null, text, null, null);
	}

	@SuppressWarnings("unchecked")
	private static void acceptUpdate(AcpUpdateFold fold, Object notificationParams) {
		if (notificationParams instanceof Map<?, ?> map) {
			fold.accept((Map<String, Object>) map);
		}
	}

	private static AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		try {
			return new AcpSchema.ReadTextFileResponse(Files.readString(Paths.get(request.path())));
		}
		catch (IOException ex) {
			// The agent asked; an unreadable file is an answer, not a reason to fail the
			// run.
			logger.warn("ACP fs/read_text_file failed for {}: {}", request.path(), ex.getMessage());
			return new AcpSchema.ReadTextFileResponse("");
		}
	}

	private static AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		try {
			Path path = Paths.get(request.path());
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, (request.content() != null) ? request.content() : "");
		}
		catch (IOException ex) {
			logger.warn("ACP fs/write_text_file failed for {}: {}", request.path(), ex.getMessage());
		}
		return new AcpSchema.WriteTextFileResponse();
	}

	/**
	 * Approve a permission request by selecting the agent's own allow option.
	 *
	 * <p>
	 * A programmatic caller has nobody to prompt, so a request that is not answered
	 * stalls the run until the request timeout. The option id is taken from the agent's
	 * offered list rather than assumed, because the ids are agent-defined.
	 */
	private static AcpSchema.RequestPermissionResponse approve(AcpSchema.RequestPermissionRequest request) {
		List<AcpSchema.PermissionOption> options = (request.options() != null) ? request.options() : List.of();
		for (AcpSchema.PermissionOption option : options) {
			if (option.kind() == AcpSchema.PermissionOptionKind.ALLOW_ALWAYS
					|| option.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE) {
				// Null outcome for the same reason as textContent above: the SDK writes
				// the `outcome` discriminator twice when the field is populated.
				return new AcpSchema.RequestPermissionResponse(
						new AcpSchema.PermissionSelected(null, option.optionId()));
			}
		}
		logger.warn("ACP session/request_permission offered no allow option; cancelling");
		return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled(null));
	}

	private Path locateTrajectory(String sessionId, Path workingDirectory, Instant startedAt) {
		try {
			return this.profile.trajectoryLocator().locate(new AcpSessionRef(sessionId, workingDirectory, startedAt));
		}
		catch (RuntimeException ex) {
			logger.warn("{} trajectory lookup failed for session {}: {}", this.profile.providerKey(), sessionId,
					ex.getMessage());
			return null;
		}
	}

	private List<AcpToolStep> nameSteps(AcpUpdateFold fold) {
		List<AcpToolStep> named = new ArrayList<>();
		for (AcpToolStep step : fold.toolSteps()) {
			String name = this.profile.toolName(step, fold.toolMeta(step.toolCallId()));
			named.add(new AcpToolStep(step.toolCallId(), name, step.title(), step.kind(), step.status(),
					step.locations(), step.rawInput(), step.rawOutput()));
		}
		return named;
	}

	private static String agentName(AcpSchema.InitializeResponse initialize) {
		return (initialize.agentInfo() != null) ? initialize.agentInfo().name() : null;
	}

	private static String agentVersion(AcpSchema.InitializeResponse initialize) {
		return (initialize.agentInfo() != null) ? initialize.agentInfo().version() : null;
	}

	// ---------------------------------------------------------------------
	// Response mapping
	// ---------------------------------------------------------------------

	private AgentResponse toAgentResponse(AcpRunRecord run, AgentOptions options) {
		boolean successful = run.endedTurn();
		String finishReason = successful ? "SUCCESS" : run.stopReason();

		Map<String, Object> providerFields = new LinkedHashMap<>();
		providerFields.put("successful", successful);
		providerFields.put("stopReason", run.stopReason());
		providerFields.put("sessionId", run.sessionId());
		providerFields.put("toolCallCount", run.toolSteps().size());
		providerFields.put("toolCallNames", run.toolSteps().stream().map(AcpToolStep::name).toList());
		providerFields.put("thoughtChunkCount", run.thoughtChunkCount());
		if (run.agentName() != null) {
			providerFields.put("agentName", run.agentName());
		}
		if (run.agentVersion() != null) {
			providerFields.put("agentVersion", run.agentVersion());
		}
		if (run.trajectory() != null) {
			providerFields.put("trajectoryPath", run.trajectory().toString());
		}
		if (!run.unknownUpdateKinds().isEmpty()) {
			// Carried, not just logged: an agent that has outrun this SDK version should
			// be visible to whoever is reading the run, not only to whoever reads logs.
			providerFields.put("unknownUpdateKinds", run.unknownUpdateKinds());
		}
		providerFields.putAll(this.profile.providerFields(run));

		if (this.captureEnabled && run.trajectory() != null) {
			Object capture = this.profile.capture(run);
			if (capture != null) {
				providerFields.put("phaseCapture", capture);
			}
		}

		AgentGeneration generation = new AgentGeneration(run.answer(),
				new AgentGenerationMetadata(finishReason, Map.copyOf(providerFields)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model(modelLabel(options))
			.duration(run.duration())
			.sessionId((run.sessionId() != null) ? run.sessionId() : "")
			.providerFields(Map.copyOf(providerFields))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private AgentResponse toErrorResponse(Exception ex) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName();
		AgentGeneration generation = new AgentGeneration(message,
				new AgentGenerationMetadata("ERROR", Map.of("error", message)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model(this.profile.defaultModelLabel())
			.duration(Duration.ZERO)
			.providerFields(Map.of("error", message, "successful", false))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private String modelLabel(AgentOptions options) {
		if (options != null && options.getModel() != null) {
			return options.getModel();
		}
		return this.profile.defaultModelLabel();
	}

	// ---------------------------------------------------------------------
	// Request shaping
	// ---------------------------------------------------------------------

	/**
	 * ACP has no system-prompt field, so portable {@code systemInstructions} are
	 * prepended to the goal — the same choice every non-ACP adapter here makes for CLIs
	 * without a system-prompt flag.
	 */
	private String prepareGoal(AgentTaskRequest request, AgentOptions options) {
		String goal = request.goal();
		if (options != null && options.getSystemInstructions() != null && !options.getSystemInstructions().isEmpty()) {
			return options.getSystemInstructions() + "\n\n" + goal;
		}
		return goal;
	}

	private Path resolveWorkingDirectory(AgentTaskRequest request, AgentOptions options) {
		if (request.workingDirectory() != null) {
			return request.workingDirectory();
		}
		if (options != null && options.getWorkingDirectory() != null) {
			return Paths.get(options.getWorkingDirectory());
		}
		return Paths.get(System.getProperty("user.dir"));
	}

	public static final class Builder {

		private final AcpAgentProfile profile;

		private String command;

		private AgentOptions defaultOptions;

		private boolean captureEnabled = true;

		private Builder(AcpAgentProfile profile) {
			this.profile = profile;
		}

		public Builder command(String command) {
			this.command = command;
			return this;
		}

		public Builder defaultOptions(AgentOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Trajectory capture is enabled by default; this is the opt-out.
		 * @param captureEnabled whether to parse the located trajectory
		 * @return this builder
		 */
		public Builder captureEnabled(boolean captureEnabled) {
			this.captureEnabled = captureEnabled;
			return this;
		}

		public AcpAgentModel build() {
			return new AcpAgentModel(this.profile, this.command, this.defaultOptions, this.captureEnabled);
		}

	}

}
