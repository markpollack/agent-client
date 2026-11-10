/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.amp.AmpAgentOptions;
import io.github.markpollack.agents.ampsdk.AmpClient;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;
import io.github.markpollack.agents.ampsdk.types.ExecuteResult;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.agents.model.StructuredOutputPromptHelper;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.sandbox.Sandbox;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link AgentApi} for Sourcegraph Amp CLI-based agents.
 *
 * <p>
 * This adapter bridges Spring AI's agent abstraction with the Amp CLI, providing
 * autonomous development tasks through goal-driven task execution.
 * </p>
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
// AgentModel kept for compatibility until the AgentApi migration release
@SuppressWarnings("removal")
public class AmpAgentModel implements AgentModel {

	private static final Logger logger = LoggerFactory.getLogger(AmpAgentModel.class);

	private final AmpClient ampClient;

	private final AmpAgentOptions defaultOptions;

	/**
	 * Create a new AmpAgentModel with the given client, options, and sandbox.
	 * @param ampClient the Amp CLI client
	 * @param defaultOptions default execution options
	 * @param sandbox retained for constructor compatibility; execution uses the SDK
	 * transport
	 */
	public AmpAgentModel(AmpClient ampClient, AmpAgentOptions defaultOptions, Sandbox sandbox) {
		this.ampClient = ampClient;
		this.defaultOptions = defaultOptions != null ? defaultOptions : new AmpAgentOptions();

		// Set system property for executable path if provided
		if (this.defaultOptions.getExecutablePath() != null) {
			System.setProperty("AMP_CLI_PATH", this.defaultOptions.getExecutablePath());
		}
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		// Extract goal/prompt from request, with portable system prompt prepended
		String goal = request.goal();
		if (request.options() != null && request.options().getSystemInstructions() != null
				&& !request.options().getSystemInstructions().isEmpty()) {
			goal = request.options().getSystemInstructions() + "\n\n" + goal;
		}

		// Tier 3: embed schema in prompt when jsonSchema is present
		if (request.options() != null && request.options().getJsonSchema() != null) {
			goal = StructuredOutputPromptHelper.wrapGoalWithSchema(goal, request.options().getJsonSchema());
		}

		logger.info("Executing Amp agent with goal: {}", goal);

		// Merge options
		AmpAgentOptions options = mergeOptions(request);

		// Convert to ExecuteOptions
		ExecuteOptions executeOptions = toExecuteOptions(options, request);

		// Execute via SDK
		ExecuteResult result = ampClient.execute(goal, executeOptions);

		// Convert to AgentResponse
		return toAgentResponse(result);
	}

	@Override
	public boolean isAvailable() {
		try {
			return ampClient.isAvailable();
		}
		catch (Exception e) {
			logger.warn("Amp CLI availability check failed: {}", e.getMessage());
			return false;
		}
	}

	private AmpAgentOptions mergeOptions(AgentTaskRequest request) {
		// Start with default options
		AmpAgentOptions merged = new AmpAgentOptions();
		merged.setModel(defaultOptions.getModel());
		merged.setTimeout(defaultOptions.getTimeout());
		merged.setDangerouslyAllowAll(defaultOptions.isDangerouslyAllowAll());
		merged.setExecutablePath(defaultOptions.getExecutablePath());

		// Override with request-specific options if present
		if (request.options() != null && request.options() instanceof AmpAgentOptions requestOptions) {
			if (requestOptions.getModel() != null) {
				merged.setModel(requestOptions.getModel());
			}
			if (requestOptions.getTimeout() != null) {
				merged.setTimeout(requestOptions.getTimeout());
			}
			merged.setDangerouslyAllowAll(requestOptions.isDangerouslyAllowAll());
		}

		return merged;
	}

	private ExecuteOptions toExecuteOptions(AmpAgentOptions options, AgentTaskRequest request) {
		ExecuteOptions.Builder builder = ExecuteOptions.builder()
			.dangerouslyAllowAll(options.isDangerouslyAllowAll())
			.timeout(options.getTimeout());

		if (request.options() != null && request.options().getMcpServerDefinitions() != null) {
			builder.mcpServers(toAmpMcpServers(request.options().getMcpServerDefinitions()));
		}

		if (options.getModel() != null) {
			builder.model(options.getModel());
		}

		return builder.build();
	}

	/**
	 * Translates portable definitions into {@code amp.mcpServers} entries. Amp detects a
	 * remote server's transport from its URL, so SSE and HTTP servers share one shape.
	 */
	static Map<String, Object> toAmpMcpServers(Map<String, McpServerDefinition> definitions) {
		Map<String, Object> servers = new LinkedHashMap<>();
		definitions.forEach((name, definition) -> servers.put(name, toAmpMcpServer(definition)));
		return servers;
	}

	private static Map<String, Object> toAmpMcpServer(McpServerDefinition definition) {
		Map<String, Object> config = new LinkedHashMap<>();
		if (definition instanceof McpServerDefinition.StdioDefinition stdio) {
			config.put("command", stdio.command());
			putIfPresent(config, "args", stdio.args().isEmpty() ? null : stdio.args());
			putIfPresent(config, "env", stdio.env().isEmpty() ? null : stdio.env());
		}
		else if (definition instanceof McpServerDefinition.SseDefinition sse) {
			config.put("url", sse.url());
			putIfPresent(config, "headers", sse.headers().isEmpty() ? null : sse.headers());
		}
		else if (definition instanceof McpServerDefinition.HttpDefinition http) {
			config.put("url", http.url());
			putIfPresent(config, "headers", http.headers().isEmpty() ? null : http.headers());
		}
		return config;
	}

	private static void putIfPresent(Map<String, Object> config, String key, Object value) {
		if (value != null) {
			config.put(key, value);
		}
	}

	private AgentResponse toAgentResponse(ExecuteResult result) {
		// Create generation with output
		AgentGeneration generation = new AgentGeneration(result.getOutput(),
				new AgentGenerationMetadata(result.getModel(), Map.of("exitCode", result.getExitCode())));

		// Create response metadata with sessionId (empty for Amp)
		AgentResponseMetadata metadata = new AgentResponseMetadata(result.getModel(), result.getDuration(), "",
				Map.of("exitCode", result.getExitCode(), "successful", result.isSuccessful()));

		return new AgentResponse(List.of(generation), metadata);
	}

}
