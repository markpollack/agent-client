/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.hooks.HookCallback;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.IterableAgentModel;
import io.github.markpollack.agents.model.StreamingAgentModel;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.journal.trace.TraceContentMode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Claude Code CLI agent model implementing all three programming models:
 * blocking/imperative, reactive/streaming, and iterator-based.
 *
 * <p>
 * This is the primary entry point for Claude Code CLI integration. It supports:
 * </p>
 * <ul>
 * <li>{@link io.github.markpollack.agents.model.AgentApi#call} - Blocking execution</li>
 * <li>{@link StreamingAgentModel#stream} - Reactive Flux-based streaming</li>
 * <li>{@link IterableAgentModel#iterate} - Iterator-based consumption</li>
 * </ul>
 *
 * <p>
 * Hooks can be registered to intercept and modify tool executions:
 * </p>
 * <pre>{@code
 * var model = ClaudeAgentModel.builder()
 *     .workingDirectory(Paths.get("/my/project"))
 *     .timeout(Duration.ofMinutes(5))
 *     .build();
 *
 * // Register a pre-tool-use hook to block dangerous commands
 * model.registerPreToolUse("Bash", input -> {
 *     var preToolUse = (HookInput.PreToolUseInput) input;
 *     String cmd = preToolUse.getArgument("command", String.class).orElse("");
 *     if (cmd.contains("rm -rf")) {
 *         return HookOutput.block("Dangerous command blocked");
 *     }
 *     return HookOutput.allow();
 * });
 *
 * // Use any programming model
 * AgentResponse response = model.call(request);           // Blocking
 * Flux<AgentResponse> flux = model.stream(request);       // Reactive
 * Iterator<AgentResponse> iter = model.iterate(request);  // Iterator
 * }</pre>
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
// AgentModel kept for compatibility until the AgentApi migration release
@SuppressWarnings("removal")
public class ClaudeAgentModel implements AgentModel, StreamingAgentModel, IterableAgentModel, AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeAgentModel.class);

	/**
	 * Default executor using cached thread pool with daemon threads. Java 21 users can
	 * provide {@code Executors.newVirtualThreadPerTaskExecutor()} via the builder.
	 */
	private static final ExecutorService DEFAULT_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
		private final AtomicInteger counter = new AtomicInteger(0);

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "claude-agent-async-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

	private final Path workingDirectory;

	private final Duration timeout;

	private final String claudePath;

	private final HookRegistry hookRegistry;

	/**
	 * The sync clients this model currently has in flight, so {@link #interrupt()} can
	 * reach them. Registered once a client is built and removed when its call ends.
	 *
	 * <p>
	 * Without this nothing can stop a call already running: each execution opens its
	 * client in local scope, so no handle escapes, and a blocked
	 * {@code receiveResponse()} has no other way out. Closing the client reaches the
	 * SDK's {@code StreamingTransport.close()}, which destroys the CLI process tree
	 * descendants-first — which is the point, because those children go on editing the
	 * project after a cancel.
	 *
	 * <p>
	 * The availability probe's client is deliberately not registered: it closes inside
	 * its own method and cannot be the ongoing work a cancel is aimed at, so tracking it
	 * would only let a cancel turn a health check into a spurious failure.
	 */
	private final Set<ClaudeSyncClient> inFlight = ConcurrentHashMap.newKeySet();

	private final ClaudeAgentOptions defaultOptions;

	private final Executor asyncExecutor;

	private final Consumer<ParsedMessage> messageListener;

	private final Path traceDir;

	/**
	 * Journal 1.x TraceContentMode when the legacy dependencies are present; otherwise
	 * null.
	 */
	private final Object traceContentMode;

	private final boolean legacyTrace;

	private final boolean archiveTranscript;

	private final ClaudeCaptureOptions captureOptions;

	private ClaudeAgentModel(Builder builder) {
		this.workingDirectory = builder.workingDirectory;
		this.timeout = builder.timeout;
		this.claudePath = builder.claudePath;
		this.hookRegistry = builder.hookRegistry != null ? builder.hookRegistry : new HookRegistry();
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions : new ClaudeAgentOptions();
		this.asyncExecutor = builder.asyncExecutor != null ? builder.asyncExecutor : DEFAULT_EXECUTOR;
		this.messageListener = builder.messageListener;
		this.traceDir = builder.traceDir;
		this.legacyTrace = LegacyTrace.available();
		this.traceContentMode = builder.contentMode(this.legacyTrace);
		this.archiveTranscript = builder.archiveTranscript;
		this.captureOptions = builder.captureOptions;
	}

	/**
	 * Creates a new builder for ClaudeAgentModel.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	// ========== Hook Registration API ==========

	/**
	 * Registers a pre-tool-use hook for specific tools.
	 * @param toolPattern regex pattern for tool names
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerPreToolUse(String toolPattern, HookCallback callback) {
		return hookRegistry.registerPreToolUse(toolPattern, callback);
	}

	/**
	 * Registers a pre-tool-use hook for all tools.
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerPreToolUse(HookCallback callback) {
		return hookRegistry.registerPreToolUse(callback);
	}

	/**
	 * Registers a post-tool-use hook for specific tools.
	 * @param toolPattern regex pattern for tool names
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerPostToolUse(String toolPattern, HookCallback callback) {
		return hookRegistry.registerPostToolUse(toolPattern, callback);
	}

	/**
	 * Registers a post-tool-use hook for all tools.
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerPostToolUse(HookCallback callback) {
		return hookRegistry.registerPostToolUse(callback);
	}

	/**
	 * Registers a user-prompt-submit hook.
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerUserPromptSubmit(HookCallback callback) {
		return hookRegistry.registerUserPromptSubmit(callback);
	}

	/**
	 * Registers a stop hook.
	 * @param callback the hook callback
	 * @return the generated hook ID
	 */
	public String registerStop(HookCallback callback) {
		return hookRegistry.registerStop(callback);
	}

	/**
	 * Unregisters a hook by ID.
	 * @param hookId the hook ID to remove
	 * @return true if removed, false if not found
	 */
	public boolean unregisterHook(String hookId) {
		return hookRegistry.unregister(hookId);
	}

	/**
	 * Gets the hook registry for advanced configuration.
	 * @return the hook registry
	 */
	public HookRegistry getHookRegistry() {
		return hookRegistry;
	}

	// ========== AgentApi (Blocking) ==========

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		Instant start = Instant.now();
		Path cwd = request.workingDirectory() != null ? request.workingDirectory() : workingDirectory;
		// Resolve the legacy trace target before client setup: fail fast on config
		// errors.
		TraceTarget trace = resolveTraceTarget();
		ClaudeCaptureSession acquisition = captureOptions == null ? null : new ClaudeCaptureSession(captureOptions);
		CLIOptions options = ClaudeCliOptionsMapper.build(request, getEffectiveOptions(request), timeout,
				acquisition == null ? null : acquisition.wire()::onBytes);
		var result = new ClaudeExecutionResult();
		Object capture = null;
		String failure = null;
		ClaudeSyncClient tracked = null;
		try (ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(cwd)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build()) {
			tracked = client;
			inFlight.add(client);
			client.connect(formatPrompt(request));
			capture = consume(client.receiveResponse(), request, options, trace, result, acquisition);
		}
		catch (Exception ex) {
			failure = ex.toString();
		}
		finally {
			if (tracked != null) {
				inFlight.remove(tracked);
			}
		}
		// The client is closed: the CLI's own transcript is as flushed as it will be.
		Path archived = harvestTranscript(result.sessionId(), trace, cwd);
		ClaudeAcquisition reference = acquisition == null ? null
				: acquisition.seal(result, failure, options, cwd, request.goal());
		AgentResponse response = result.response(options.getModel(), Duration.between(start, Instant.now()), reference,
				failure);
		if (capture != null) {
			JournalOneTrace.legacyFields(response.getMetadata(), capture, trace.path(), archived);
		}
		return response;
	}

	/**
	 * Every message reaches the execution result, the optional acquisition and the
	 * listener. With Journal 1.x present the legacy parser consumes the stream and owns
	 * the derived trace; without it the stream is drained here and no legacy field
	 * exists.
	 */
	private Object consume(Iterator<ParsedMessage> response, AgentTaskRequest request, CLIOptions options,
			TraceTarget trace, ClaudeExecutionResult result, ClaudeCaptureSession acquisition) {
		Iterator<ParsedMessage> teed = new TeeingIterator(response, message -> {
			if (acquisition != null) {
				acquisition.observeProcess();
			}
			result.accept(message);
			if (messageListener != null) {
				messageListener.accept(message);
			}
		});
		if (!this.legacyTrace) {
			while (teed.hasNext()) {
				teed.next();
			}
			return null;
		}
		// Claude Code never echoes maxTurns; without it a trace cannot tell a finished
		// run from a cut-off one.
		return JournalOneTrace.parse(teed, trace.runId(), formatPrompt(request), trace.path(), this.traceContentMode,
				options.getMaxTurns());
	}

	// ========== StreamingAgentModel (Reactive) ==========

	@Override
	public Flux<AgentResponse> stream(AgentTaskRequest request) {
		Sinks.Many<AgentResponse> sink = Sinks.many().multicast().onBackpressureBuffer();

		asyncExecutor.execute(() -> {
			try {
				streamInternal(request, sink);
			}
			catch (Exception e) {
				logger.error("Streaming failed", e);
				sink.tryEmitError(e);
			}
		});

		return sink.asFlux();
	}

	// ========== IterableAgentModel (Iterator) ==========

	@Override
	public Iterator<AgentResponse> iterate(AgentTaskRequest request) {
		Path effectiveWorkingDir = request.workingDirectory() != null ? request.workingDirectory() : workingDirectory;
		CLIOptions options = buildCLIOptions(request);

		ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(effectiveWorkingDir)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build();
		this.inFlight.add(client);

		String prompt = formatPrompt(request);
		client.connect(prompt);
		Iterator<ParsedMessage> messageIterator = client.receiveResponse();

		return new Iterator<>() {
			private AgentResponse next = null;

			private boolean closed = false;

			@Override
			public boolean hasNext() {
				if (closed) {
					return false;
				}
				if (next != null) {
					return true;
				}
				while (messageIterator.hasNext()) {
					ParsedMessage parsed = messageIterator.next();
					if (messageListener != null) {
						messageListener.accept(parsed);
					}
					if (parsed.isRegularMessage()) {
						AgentResponse response = convertMessageToResponse(parsed.asMessage());
						if (response != null) {
							next = response;
							return true;
						}
					}
				}
				// Close the client when iteration is complete
				if (!closed) {
					closed = true;
					ClaudeAgentModel.this.inFlight.remove(client);
					client.close();
				}
				return false;
			}

			@Override
			public AgentResponse next() {
				if (!hasNext()) {
					throw new java.util.NoSuchElementException();
				}
				AgentResponse result = next;
				next = null;
				return result;
			}
		};
	}

	// ========== Availability Check ==========

	@Override
	public boolean isAvailable() {
		try {
			// Try to create a client to verify CLI is available
			ClaudeSyncClient client = ClaudeClient.sync()
				.workingDirectory(workingDirectory != null ? workingDirectory : Path.of(System.getProperty("user.dir")))
				.timeout(Duration.ofSeconds(5))
				.claudePath(claudePath)
				.build();
			client.close();
			return true;
		}
		catch (Exception e) {
			logger.debug("Claude CLI not available: {}", e.getMessage());
			return false;
		}
	}

	// ========== AutoCloseable ==========

	/**
	 * Stop whatever this model is running now, including the CLI process tree beneath it.
	 *
	 * <p>
	 * Closes every in-flight client, which ends the blocked {@code receiveResponse()} so
	 * the call it belongs to throws rather than running on. Safe to call from another
	 * thread — that is the only way it is useful — and safe to call when nothing is
	 * running, when it does nothing. One client failing to close does not stop the rest.
	 *
	 * <p>
	 * This is what an ACP cancel notification needs: the child processes are the reason
	 * it matters, since they keep modifying the project after the request is abandoned.
	 */
	public void interrupt() {
		Iterator<ClaudeSyncClient> clients = this.inFlight.iterator();
		while (clients.hasNext()) {
			ClaudeSyncClient client = clients.next();
			clients.remove();
			try {
				client.close();
			}
			catch (RuntimeException ex) {
				logger.warn("Interrupt: a client failed to close; continuing", ex);
			}
		}
	}

	@Override
	public void close() {
		interrupt();
		hookRegistry.clear();
	}

	// ========== Internal Implementation ==========

	private void streamInternal(AgentTaskRequest request, Sinks.Many<AgentResponse> sink) {
		Path effectiveWorkingDir = request.workingDirectory() != null ? request.workingDirectory() : workingDirectory;
		CLIOptions options = buildCLIOptions(request);

		ClaudeSyncClient tracked = null;
		try (ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(effectiveWorkingDir)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build()) {
			tracked = client;
			this.inFlight.add(client);

			String prompt = formatPrompt(request);
			client.connect(prompt);

			Iterator<ParsedMessage> response = client.receiveResponse();
			while (response.hasNext()) {
				ParsedMessage parsed = response.next();
				if (messageListener != null) {
					messageListener.accept(parsed);
				}
				if (parsed.isRegularMessage()) {
					AgentResponse agentResponse = convertMessageToResponse(parsed.asMessage());
					if (agentResponse != null) {
						sink.tryEmitNext(agentResponse).orThrow();
					}
				}
			}

			sink.tryEmitComplete();

		}
		catch (Exception e) {
			sink.tryEmitError(e);
		}
		finally {
			if (tracked != null) {
				this.inFlight.remove(tracked);
			}
		}
	}

	private AgentResponse convertMessageToResponse(Message message) {
		if (message instanceof AssistantMessage assistantMessage) {
			String text = assistantMessage.getTextContent().orElse("");
			if (!text.isEmpty()) {
				AgentGenerationMetadata metadata = new AgentGenerationMetadata("STREAMING", Map.of());
				List<AgentGeneration> generations = List.of(new AgentGeneration(text, metadata));
				return new AgentResponse(generations, new AgentResponseMetadata());
			}
		}
		else if (message instanceof ResultMessage resultMessage) {
			String text = resultMessage.result() != null ? resultMessage.result() : "";
			String finishReason = resultMessage.isError() ? "ERROR" : "SUCCESS";
			AgentGenerationMetadata metadata = new AgentGenerationMetadata(finishReason, Map.of());
			List<AgentGeneration> generations = List.of(new AgentGeneration(text, metadata));
			return new AgentResponse(generations, new AgentResponseMetadata());
		}
		return null;
	}

	CLIOptions buildCLIOptions(AgentTaskRequest request) {
		return ClaudeCliOptionsMapper.build(request, getEffectiveOptions(request), timeout);
	}

	private ClaudeAgentOptions getEffectiveOptions(AgentTaskRequest request) {
		if (request.options() instanceof ClaudeAgentOptions requestOptions) {
			return requestOptions;
		}
		return defaultOptions;
	}

	private String formatPrompt(AgentTaskRequest request) {
		return request.goal();
	}

	// Package-private for testing
	McpServerConfig toClaudeMcpServerConfig(McpServerDefinition definition) {
		return ClaudeCliOptionsMapper.toClaudeMcpServerConfig(definition);
	}

	// ========== Legacy derived trace ==========

	private record TraceTarget(String runId, Path path) {
	}

	private TraceTarget resolveTraceTarget() {
		if (this.traceDir == null) {
			return new TraceTarget("agent-run", null);
		}
		Path absoluteTraceDir = this.traceDir.toAbsolutePath().normalize();
		try {
			Files.createDirectories(absoluteTraceDir);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to create trace directory: " + absoluteTraceDir, ex);
		}
		String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
			.format(Instant.now().atZone(ZoneOffset.UTC));
		String runId = "agent-run-" + timestamp + "-" + UUID.randomUUID();
		return new TraceTarget(runId, absoluteTraceDir.resolve(runId + ".jsonl"));
	}

	/**
	 * Best-effort verbatim archive of the CLI's own session transcript into
	 * {@code <traceDir>/transcripts/}. A missing transcript logs a warning and returns
	 * null; callers must never require archive success.
	 */
	private Path harvestTranscript(String sessionId, TraceTarget trace, Path effectiveWorkingDir) {
		if (!archiveTranscript || trace.path() == null) {
			return null;
		}
		Path projectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
		if (!Files.isDirectory(projectsDir)) {
			logger.warn("Transcript archive: {} does not exist; is this a Claude CLI environment?", projectsDir);
			return null;
		}
		try {
			Path transcript = findTranscript(sessionId, projectsDir, effectiveWorkingDir);
			if (transcript == null) {
				logger.warn("Transcript archive: no transcript found (sessionId={}, projectsDir={}, cwd={})", sessionId,
						projectsDir, effectiveWorkingDir);
				return null;
			}
			Path destDir = trace.path().getParent().resolve("transcripts");
			Files.createDirectories(destDir);
			Path dest = destDir.resolve(trace.runId() + ".transcript.jsonl");
			Files.copy(transcript, dest, StandardCopyOption.REPLACE_EXISTING);
			return dest;
		}
		catch (IOException ex) {
			logger.warn("Transcript archive failed (best-effort): {}", ex.getMessage());
			return null;
		}
	}

	static Path findTranscript(String sessionId, Path projectsDir, Path effectiveWorkingDir) throws IOException {
		Path transcript = null;
		if (sessionId != null && !sessionId.isBlank()) {
			String fileName = sessionId + ".jsonl";
			try (java.util.stream.Stream<Path> stream = Files.walk(projectsDir, 2)) {
				transcript = stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
			}
		}
		if (transcript == null && (sessionId == null || sessionId.isBlank())) {
			// Only a run without an emitted identity may fall back to the newest
			// transcript
			// under the working directory's project slug; a known identity with no match
			// stays unarchived rather than being given another run's transcript.
			String slug = effectiveWorkingDir.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
			Path slugDir = projectsDir.resolve(slug);
			if (Files.isDirectory(slugDir)) {
				try (java.util.stream.Stream<Path> stream = Files.list(slugDir)) {
					transcript = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
						.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
						.orElse(null);
				}
			}
		}
		return transcript;
	}

	// ========== Builder ==========

	/**
	 * Builder for ClaudeAgentModel.
	 */
	public static class Builder {

		private Path workingDirectory;

		private Duration timeout = Duration.ofMinutes(10);

		private String claudePath;

		private HookRegistry hookRegistry;

		private ClaudeAgentOptions defaultOptions;

		private Executor asyncExecutor;

		private Consumer<ParsedMessage> messageListener;

		private Path traceDir;

		private Object traceContentMode;

		private String traceContentModeName;

		private ClaudeCaptureOptions captureOptions;

		private boolean archiveTranscript = true;

		private Builder() {
		}

		/**
		 * Sets the working directory for CLI execution.
		 * @param workingDirectory the working directory
		 * @return this builder
		 */
		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		/**
		 * Sets the default timeout for operations.
		 * @param timeout the timeout duration
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Sets the path to the Claude CLI executable.
		 * @param claudePath the path to Claude CLI
		 * @return this builder
		 */
		public Builder claudePath(String claudePath) {
			this.claudePath = claudePath;
			return this;
		}

		/**
		 * Sets a pre-configured hook registry.
		 * @param hookRegistry the hook registry
		 * @return this builder
		 */
		public Builder hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		/**
		 * Sets the default agent options.
		 * @param defaultOptions the default options
		 * @return this builder
		 */
		public Builder defaultOptions(ClaudeAgentOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Sets the executor for async operations (streaming, iteration).
		 * <p>
		 * By default, uses a cached thread pool with daemon threads. Java 21 users can
		 * provide {@code Executors.newVirtualThreadPerTaskExecutor()} for virtual thread
		 * support.
		 * </p>
		 * <pre>{@code
		 * // Java 21 with virtual threads:
		 * ClaudeAgentModel.builder()
		 *     .asyncExecutor(Executors.newVirtualThreadPerTaskExecutor())
		 *     .build();
		 * }</pre>
		 * @param asyncExecutor the executor for async operations
		 * @return this builder
		 */
		public Builder asyncExecutor(Executor asyncExecutor) {
			this.asyncExecutor = asyncExecutor;
			return this;
		}

		/**
		 * Sets a listener that receives every {@link ParsedMessage} from the Claude CLI
		 * before any filtering. Useful for capturing tool calls, thinking blocks, and
		 * cost data.
		 * @param messageListener the listener (nullable)
		 * @return this builder
		 */
		public Builder messageListener(Consumer<ParsedMessage> messageListener) {
			this.messageListener = messageListener;
			return this;
		}

		/**
		 * Sets the directory for JSONL trace files. Each {@code call()} writes one
		 * Claude-specific JSONL trace file containing the events observed during
		 * SessionLogParser parsing (tool calls, tool results, text, thinking blocks and
		 * result events). Null (default) disables tracing. When tracing is enabled, the
		 * CLI's own session transcript is also archived verbatim into
		 * {@code <traceDir>/transcripts/} by default; see
		 * {@link #archiveTranscript(boolean)}. Lossless original-byte acquisition is the
		 * separate, explicit {@link #capture(ClaudeCaptureOptions)} surface. Requires the
		 * optional Journal 1.x dependencies (journal-core, claude-code-capture) at build
		 * time; see docs/legacy-tracing-migration.md.
		 * @param traceDir the trace output directory (created on first use if absent)
		 * @return this builder
		 */
		public Builder traceDir(Path traceDir) {
			this.traceDir = traceDir;
			return this;
		}

		/**
		 * Sets the content capture policy for trace files. Default
		 * {@link TraceContentMode#TRUNCATED}: full thinking/text/tool_result content,
		 * truncated per item at 60KB. {@link TraceContentMode#FULL} never truncates;
		 * {@link TraceContentMode#LENGTHS} pins the lengths-only disk footprint.
		 * Content-carrying traces may include prompts, file contents and command output;
		 * treat them as sensitive local artifacts.
		 * @param traceContentMode the content mode (null resets to default)
		 * @return this builder
		 */
		public Builder traceContentMode(TraceContentMode traceContentMode) {
			this.traceContentMode = traceContentMode;
			return this;
		}

		/**
		 * Sets the trace content policy by name (FULL, TRUNCATED or LENGTHS,
		 * case-insensitive), for configuration binding that must not reference Journal
		 * 1.x types. Requires the optional Journal 1.x dependencies at build time.
		 * @param traceContentModeName the policy name, or null for the default
		 * @return this builder
		 */
		public Builder traceContentModeName(String traceContentModeName) {
			this.traceContentModeName = traceContentModeName;
			return this;
		}

		/**
		 * Resolved under the presence check; the TRUNCATED default is applied by the
		 * parser.
		 */
		Object contentMode(boolean legacyAvailable) {
			if (!legacyAvailable) {
				return null;
			}
			if (this.traceContentMode != null) {
				return this.traceContentMode;
			}
			return this.traceContentModeName == null ? null : JournalOneTrace.contentMode(this.traceContentModeName);
		}

		/**
		 * Sets one invocation's lossless acquired-source destination (original bytes plus
		 * the exact native session files). Only the blocking call captures. Independent
		 * of {@link #traceDir(Path)}: both may be configured.
		 * @param captureOptions new-directory and invocation settings
		 * @return this builder
		 */
		public Builder capture(ClaudeCaptureOptions captureOptions) {
			this.captureOptions = captureOptions;
			return this;
		}

		/**
		 * Enables or disables verbatim archival of the CLI session transcript into
		 * {@code <traceDir>/transcripts/} after each call. Default true (effective only
		 * when a {@link #traceDir(Path)} is set). Best-effort: a missing transcript logs
		 * a warning, never fails the call. Explicit acquisition always preserves native
		 * sources, so it cannot be combined with {@code false}.
		 * @param archiveTranscript whether to archive session transcripts
		 * @return this builder
		 */
		public Builder archiveTranscript(boolean archiveTranscript) {
			this.archiveTranscript = archiveTranscript;
			return this;
		}

		/**
		 * Builds the ClaudeAgentModel.
		 * @return the configured model
		 */
		public ClaudeAgentModel build() {
			if (!archiveTranscript && captureOptions != null) {
				throw new IllegalArgumentException("Explicit acquisition requires native source lookup");
			}
			if (traceDir != null || traceContentMode != null || traceContentModeName != null) {
				LegacyTrace.require("Legacy derived tracing (traceDir/traceContentMode)");
			}
			if (workingDirectory == null) {
				workingDirectory = Path.of(System.getProperty("user.dir"));
			}
			return new ClaudeAgentModel(this);
		}

	}

}
