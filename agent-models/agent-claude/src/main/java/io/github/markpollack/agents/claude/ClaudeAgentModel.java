/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import io.github.markpollack.journal.claude.TraceContentMode;
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
import java.util.LinkedHashMap;
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
 * <li>{@link AgentModel#call} - Blocking execution</li>
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

	private final ClaudeAgentOptions defaultOptions;

	private final Executor asyncExecutor;

	private final Consumer<ParsedMessage> messageListener;

	private final Path traceDir;

	private final TraceContentMode traceContentMode;

	private final boolean archiveTranscript;

	private ClaudeAgentModel(Builder builder) {
		this.workingDirectory = builder.workingDirectory;
		this.timeout = builder.timeout;
		this.claudePath = builder.claudePath;
		this.hookRegistry = builder.hookRegistry != null ? builder.hookRegistry : new HookRegistry();
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions : new ClaudeAgentOptions();
		this.asyncExecutor = builder.asyncExecutor != null ? builder.asyncExecutor : DEFAULT_EXECUTOR;
		this.messageListener = builder.messageListener;
		this.traceDir = builder.traceDir;
		this.traceContentMode = builder.traceContentMode;
		this.archiveTranscript = builder.archiveTranscript;
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

	// ========== AgentModel (Blocking) ==========

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		logger.info("Executing blocking call for goal: {}", request.goal());

		Instant startTime = Instant.now();
		StringBuilder fullText = new StringBuilder();

		// Resolve trace target before client setup — fail fast on config errors
		TraceTarget traceTarget = resolveTraceTarget();

		Path effectiveWorkingDir = request.workingDirectory() != null ? request.workingDirectory() : workingDirectory;
		CLIOptions options = buildCLIOptions(request);

		PhaseCapture capture;
		try (ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(effectiveWorkingDir)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build()) {

			String prompt = formatPrompt(request);
			client.connect(prompt);

			Iterator<ParsedMessage> response = client.receiveResponse();

			// Tee messages to listener if set, then let SessionLogParser consume
			if (messageListener != null) {
				response = new TeeingIterator(response, messageListener);
			}

			capture = SessionLogParser.parse(response, traceTarget.runId(), prompt, traceTarget.path(),
					traceContentMode);
		}
		catch (Exception e) {
			logger.error("Call failed", e);
			// Best-effort transcript salvage for the failed run (no sessionId — slug
			// fallback only); the failure cases are the ones most worth inspecting.
			harvestTranscript(null, traceTarget, effectiveWorkingDir);
			Duration duration = Duration.between(startTime, Instant.now());
			return createErrorResponse(e.getMessage(), duration);
		}

		// Client closed above — the CLI subprocess has exited, so its session
		// transcript is as flushed as it will ever be. Archive it now (best-effort).
		Path archivedTranscript = harvestTranscript(capture.sessionId(), traceTarget, effectiveWorkingDir);

		// When --json-schema is active, prefer rawResult (the constrained output)
		// over textOutput (which may contain conversational prose)
		boolean hasJsonSchema = options.getJsonSchema() != null && !options.getJsonSchema().isEmpty();
		String textOutput;
		if (hasJsonSchema && capture.rawResult() != null && !capture.rawResult().isEmpty()) {
			textOutput = capture.rawResult();
		}
		else {
			textOutput = capture.textOutput() != null && !capture.textOutput().isEmpty() ? capture.textOutput()
					: (capture.rawResult() != null ? capture.rawResult() : "");
		}

		Duration duration = Duration.between(startTime, Instant.now());
		AgentGenerationMetadata generationMetadata = new AgentGenerationMetadata("SUCCESS", Map.of());
		List<AgentGeneration> generations = List.of(new AgentGeneration(textOutput, generationMetadata));

		Map<String, Object> providerFields = new HashMap<>();
		providerFields.put("phaseCapture", capture);
		providerFields.put("inputTokens", capture.inputTokens());
		providerFields.put("outputTokens", capture.outputTokens());
		providerFields.put("thinkingTokens", capture.thinkingTokens());
		providerFields.put("totalCostUsd", capture.totalCostUsd());
		if (traceTarget.path() != null) {
			providerFields.put("tracePath", traceTarget.path().toAbsolutePath().toString());
		}
		if (archivedTranscript != null) {
			providerFields.put("transcriptPath", archivedTranscript.toAbsolutePath().toString());
		}

		AgentResponseMetadata responseMetadata = AgentResponseMetadata.builder()
			.model(getEffectiveModel())
			.duration(duration)
			.sessionId(capture.sessionId() != null ? capture.sessionId() : "")
			.providerFields(providerFields)
			.build();

		return new AgentResponse(generations, responseMetadata);
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

	@Override
	public void close() {
		hookRegistry.clear();
	}

	// ========== Internal Implementation ==========

	private void streamInternal(AgentTaskRequest request, Sinks.Many<AgentResponse> sink) {
		Path effectiveWorkingDir = request.workingDirectory() != null ? request.workingDirectory() : workingDirectory;
		CLIOptions options = buildCLIOptions(request);

		try (ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(effectiveWorkingDir)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build()) {

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
						sink.tryEmitNext(agentResponse);
					}
				}
			}

			sink.tryEmitComplete();

		}
		catch (Exception e) {
			sink.tryEmitError(e);
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
		ClaudeAgentOptions options = getEffectiveOptions(request);
		CLIOptions.Builder builder = CLIOptions.builder();

		if (options.getTimeout() != null) {
			builder.timeout(options.getTimeout());
		}
		else if (timeout != null) {
			builder.timeout(timeout);
		}

		if (options.getModel() != null) {
			builder.model(options.getModel());
		}

		// Auto-approve: Claude-specific yolo if ClaudeAgentOptions, otherwise portable
		// isAutoApprove()
		boolean autoApprove = options.isYolo();
		if (!(request.options() instanceof ClaudeAgentOptions) && request.options() != null) {
			autoApprove = request.options().isAutoApprove();
		}
		if (autoApprove) {
			builder.permissionMode(
					io.github.markpollack.claude.agent.sdk.config.PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
		}

		// Reasoning effort: Claude-native effort (full CLI range) wins; otherwise the
		// portable AgentOptions effort (low/medium/high). Reaches the CLI as
		// "--effort <level>" via extraArgs (first-class SDK CLIOptions support TBD —
		// the flag is in CLIFlagParityIT's EXCLUDED_FLAGS for this reason).
		String effort = options.getEffort();
		if (effort == null && !(request.options() instanceof ClaudeAgentOptions) && request.options() != null) {
			effort = request.options().getEffort();
		}
		if (effort != null && !effort.isBlank()) {
			builder.extraArgs(Map.of("effort", effort));
		}

		// Extended thinking
		if (options.getMaxThinkingTokens() != null) {
			builder.maxThinkingTokens(options.getMaxThinkingTokens());
		}

		// Max tokens
		if (options.getMaxTokens() != null) {
			builder.maxTokens(options.getMaxTokens());
		}

		// System prompt
		if (options.getSystemPrompt() != null) {
			SystemPrompt sp = options.getSystemPrompt();
			String promptText;
			if (sp instanceof SystemPrompt.StringPrompt stringPrompt) {
				promptText = stringPrompt.prompt();
			}
			else if (sp instanceof SystemPrompt.PresetPrompt presetPrompt) {
				promptText = presetPrompt.preset() + (presetPrompt.append() != null ? " " + presetPrompt.append() : "");
			}
			else {
				promptText = null;
			}
			if (promptText != null) {
				builder.systemPrompt(promptText);
			}
		}

		// Tool filtering
		if (options.getAllowedTools() != null && !options.getAllowedTools().isEmpty()) {
			builder.allowedTools(options.getAllowedTools());
		}
		if (options.getDisallowedTools() != null && !options.getDisallowedTools().isEmpty()) {
			builder.disallowedTools(options.getDisallowedTools());
		}

		// Structured output — check Claude-specific options first, then portable
		if (options.getJsonSchema() != null && !options.getJsonSchema().isEmpty()) {
			builder.jsonSchema(options.getJsonSchema());
		}
		else {
			AgentOptions portableOpts = request.options();
			if (portableOpts != null && portableOpts.getJsonSchema() != null
					&& !portableOpts.getJsonSchema().isEmpty()) {
				builder.jsonSchema(portableOpts.getJsonSchema());
			}
		}

		// MCP servers: merge portable definitions (from AgentOptions) with
		// Claude-specific
		// servers. Portable fields (e.g., MCP definitions) are read from
		// request.options()
		// via the AgentOptions interface. This bypasses getEffectiveOptions() which
		// requires
		// ClaudeAgentOptions. Claude-specific fields continue to go through
		// getEffectiveOptions().
		Map<String, McpServerConfig> mergedMcpServers = new LinkedHashMap<>();

		// First: portable definitions translated from the catalog (lower precedence)
		AgentOptions portableOptions = request.options();
		if (portableOptions != null && portableOptions.getMcpServerDefinitions() != null) {
			for (Map.Entry<String, McpServerDefinition> entry : portableOptions.getMcpServerDefinitions().entrySet()) {
				mergedMcpServers.put(entry.getKey(), toClaudeMcpServerConfig(entry.getValue()));
			}
		}

		// Then: Claude-specific servers override by name (higher precedence)
		if (options.getMcpServers() != null) {
			mergedMcpServers.putAll(options.getMcpServers());
		}

		if (!mergedMcpServers.isEmpty()) {
			builder.mcpServers(mergedMcpServers);
		}

		// Max turns: Claude-specific first, then portable fallback
		if (options.getMaxTurns() != null) {
			builder.maxTurns(options.getMaxTurns());
		}
		else if (request.options() != null && request.options().getMaxTurns() != null) {
			builder.maxTurns(request.options().getMaxTurns());
		}
		if (options.getMaxBudgetUsd() != null) {
			builder.maxBudgetUsd(options.getMaxBudgetUsd());
		}

		// Fallback model
		if (options.getFallbackModel() != null && !options.getFallbackModel().isEmpty()) {
			builder.fallbackModel(options.getFallbackModel());
		}

		// Append system prompt: Claude-specific first, then portable fallback
		if (options.getAppendSystemPrompt() != null && !options.getAppendSystemPrompt().isEmpty()) {
			builder.appendSystemPrompt(options.getAppendSystemPrompt());
		}
		else if (request.options() != null && request.options().getSystemInstructions() != null
				&& !request.options().getSystemInstructions().isEmpty()) {
			builder.appendSystemPrompt(request.options().getSystemInstructions());
		}

		return builder.build();
	}

	private ClaudeAgentOptions getEffectiveOptions(AgentTaskRequest request) {
		if (request.options() instanceof ClaudeAgentOptions requestOptions) {
			return requestOptions;
		}
		return defaultOptions;
	}

	private String getEffectiveModel() {
		return defaultOptions.getModel() != null ? defaultOptions.getModel() : "claude-sonnet-4-20250514";
	}

	private String formatPrompt(AgentTaskRequest request) {
		return request.goal();
	}

	// Package-private for testing
	McpServerConfig toClaudeMcpServerConfig(McpServerDefinition definition) {
		if (definition instanceof McpServerDefinition.StdioDefinition stdio) {
			return new McpServerConfig.McpStdioServerConfig(stdio.command(), stdio.args(), stdio.env());
		}
		else if (definition instanceof McpServerDefinition.SseDefinition sse) {
			return new McpServerConfig.McpSseServerConfig(sse.url(), sse.headers());
		}
		else if (definition instanceof McpServerDefinition.HttpDefinition http) {
			return new McpServerConfig.McpHttpServerConfig(http.url(), http.headers());
		}
		throw new IllegalArgumentException("Unknown McpServerDefinition type: " + definition.getClass());
	}

	private AgentResponse createErrorResponse(String errorMessage, Duration duration) {
		AgentGenerationMetadata generationMetadata = new AgentGenerationMetadata("ERROR", Map.of());
		List<AgentGeneration> generations = List.of(new AgentGeneration(errorMessage, generationMetadata));

		AgentResponseMetadata responseMetadata = AgentResponseMetadata.builder()
			.model(getEffectiveModel())
			.duration(duration)
			.build();

		return new AgentResponse(generations, responseMetadata);
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

		private TraceContentMode traceContentMode = TraceContentMode.TRUNCATED;

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
		 * Claude-specific JSONL trace file containing events observed during
		 * SessionLogParser parsing (tool calls, tool results, text, thinking blocks, and
		 * result events). These are not provider-neutral journal events. Null (default)
		 * disables tracing.
		 * <p>
		 * When tracing is enabled, the CLI's own session transcript is also archived
		 * verbatim into {@code <traceDir>/transcripts/} by default — see
		 * {@link #archiveTranscript(boolean)}.
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
		 * Content-carrying traces may include prompts, file contents, and command output
		 * — treat them as sensitive local artifacts.
		 * @param traceContentMode the content mode (null resets to default)
		 * @return this builder
		 */
		public Builder traceContentMode(TraceContentMode traceContentMode) {
			this.traceContentMode = traceContentMode != null ? traceContentMode : TraceContentMode.TRUNCATED;
			return this;
		}

		/**
		 * Enables or disables verbatim archival of the CLI session transcript into
		 * {@code <traceDir>/transcripts/} after each call. Default true (effective only
		 * when a {@link #traceDir(Path)} is set). The transcript is the backstop for
		 * content beyond the 60KB trace truncation and carries provenance the stream
		 * lacks; Claude Code garbage-collects these files, so archive-now beats
		 * analyze-later. Best-effort: a missing transcript logs a warning, never fails
		 * the call.
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
			if (workingDirectory == null) {
				workingDirectory = Path.of(System.getProperty("user.dir"));
			}
			return new ClaudeAgentModel(this);
		}

	}

	/**
	 * Pairs a run identifier with an optional trace file path.
	 */
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
	 * Archives the CLI's own session transcript ({@code ~/.claude/projects/<slug>/
	 * <sessionId>.jsonl}) verbatim into {@code <traceDir>/transcripts/}. The transcript
	 * carries data the stream may lack (provenance fields, parentUuid conversation tree)
	 * and Claude Code garbage-collects these files — archive-now beats analyze-later.
	 *
	 * <p>
	 * Best-effort by design: if a transcript is discoverable it is archived; if not, a
	 * warning with slug/session diagnostics is logged and null is returned. Callers (and
	 * CI) must never require archive success. The file is copied verbatim — field
	 * normalization is deliberately deferred; the per-line {@code version} field is the
	 * compatibility hook for any future normalization.
	 * @param sessionId the session id from the result message, or null for failed runs
	 * (falls back to newest transcript in the working directory's project slug)
	 * @param traceTarget the trace target for this run (provides traceDir + runId)
	 * @param effectiveWorkingDir the working directory the CLI ran in (slug source)
	 * @return the archived transcript path, or null if not archived
	 */
	private Path harvestTranscript(String sessionId, TraceTarget traceTarget, Path effectiveWorkingDir) {
		if (!archiveTranscript || traceTarget.path() == null) {
			return null;
		}
		Path projectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
		if (!Files.isDirectory(projectsDir)) {
			logger.warn("Transcript archive: {} does not exist — is this a Claude CLI environment?", projectsDir);
			return null;
		}
		try {
			Path transcript = null;
			if (sessionId != null && !sessionId.isBlank()) {
				// Session ids are globally unique — search by filename
				String fileName = sessionId + ".jsonl";
				try (java.util.stream.Stream<Path> stream = Files.walk(projectsDir, 2)) {
					transcript = stream.filter(p -> p.getFileName().toString().equals(fileName))
						.findFirst()
						.orElse(null);
				}
			}
			if (transcript == null) {
				// Fallback (no/unknown sessionId, e.g. failed runs): newest transcript
				// under the working directory's project slug. May mis-pick under
				// concurrent runs in the same directory — the log line records which
				// file was taken.
				String slug = effectiveWorkingDir.toAbsolutePath()
					.normalize()
					.toString()
					.replaceAll("[^A-Za-z0-9]", "-");
				Path slugDir = projectsDir.resolve(slug);
				if (Files.isDirectory(slugDir)) {
					try (java.util.stream.Stream<Path> stream = Files.list(slugDir)) {
						transcript = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
							.max(Comparator.comparingLong(p -> p.toFile().lastModified()))
							.orElse(null);
					}
				}
			}
			if (transcript == null) {
				logger.warn(
						"Transcript archive: no transcript found (sessionId={}, projectsDir={}, cwd={}) — "
								+ "session may not have flushed, or slug resolution failed",
						sessionId, projectsDir, effectiveWorkingDir);
				return null;
			}
			Path destDir = traceTarget.path().getParent().resolve("transcripts");
			Files.createDirectories(destDir);
			Path dest = destDir.resolve(traceTarget.runId() + ".transcript.jsonl");
			Files.copy(transcript, dest, StandardCopyOption.REPLACE_EXISTING);
			logger.debug("Transcript archive: {} -> {}", transcript, dest);
			return dest;
		}
		catch (IOException ex) {
			logger.warn("Transcript archive failed (best-effort): {}", ex.getMessage());
			return null;
		}
	}

	/**
	 * Iterator wrapper that forwards each message to a listener before yielding it.
	 */
	private static class TeeingIterator implements Iterator<ParsedMessage> {

		private final Iterator<ParsedMessage> delegate;

		private final Consumer<ParsedMessage> listener;

		TeeingIterator(Iterator<ParsedMessage> delegate, Consumer<ParsedMessage> listener) {
			this.delegate = delegate;
			this.listener = listener;
		}

		@Override
		public boolean hasNext() {
			return delegate.hasNext();
		}

		@Override
		public ParsedMessage next() {
			ParsedMessage msg = delegate.next();
			listener.accept(msg);
			return msg;
		}

	}

}
