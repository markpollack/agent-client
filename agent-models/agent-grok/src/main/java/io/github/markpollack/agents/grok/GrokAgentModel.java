/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.ExecuteResult;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import io.github.markpollack.journal.grok.GrokSessionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter from the agent-client SPI to the xAI Grok CLI.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class GrokAgentModel implements AgentModel {

	private static final Logger logger = LoggerFactory.getLogger(GrokAgentModel.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final GrokClient grokClient;

	private final GrokAgentOptions defaultOptions;

	public GrokAgentModel(GrokClient grokClient, GrokAgentOptions defaultOptions) {
		this.grokClient = grokClient;
		this.defaultOptions = (defaultOptions != null) ? defaultOptions : GrokAgentOptions.builder().build();
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		String goal = request.goal();
		if (request.options() != null && request.options().getSystemInstructions() != null
				&& !request.options().getSystemInstructions().isEmpty()) {
			// Grok has --system-prompt-override, but that REPLACES the CLI's own system
			// prompt rather than adding to it, which would strip the agent's tool
			// instructions. Portable systemInstructions mean "also tell it this", so they
			// are prepended to the goal — the same choice every other adapter here makes.
			goal = request.options().getSystemInstructions() + "\n\n" + goal;
		}
		logger.info("Executing Grok agent with goal of {} characters", goal.length());

		try {
			ExecuteResult result = this.grokClient.execute(goal, toExecuteOptions(mergeOptions(request), request));
			GrokPhaseCapture capture = parseCapture(result, goal);
			return toAgentResponse(result, capture);
		}
		catch (Exception ex) {
			logger.warn("Grok agent execution failed: {}", ex.getMessage());
			return toErrorResponse(ex);
		}
	}

	@Override
	public boolean isAvailable() {
		try {
			return this.grokClient.isAvailable();
		}
		catch (Exception ex) {
			logger.warn("Grok CLI availability check failed: {}", ex.getMessage());
			return false;
		}
	}

	private GrokAgentOptions mergeOptions(AgentTaskRequest request) {
		GrokAgentOptions.Builder builder = GrokAgentOptions.builder()
			.model(this.defaultOptions.getModel())
			.reasoningEffort(this.defaultOptions.getReasoningEffort())
			.timeout(this.defaultOptions.getTimeout())
			.permissionMode(this.defaultOptions.getPermissionMode())
			.maxTurns(this.defaultOptions.getMaxTurns())
			.jsonSchema(this.defaultOptions.getJsonSchema())
			.allowedTools(this.defaultOptions.getAllowedTools())
			.disallowedTools(this.defaultOptions.getDisallowedTools())
			.disableWebSearch(this.defaultOptions.isDisableWebSearch())
			.executablePath(this.defaultOptions.getExecutablePath());

		// Portable fallbacks — the path taken when the caller passes provider-neutral
		// options, which is how a cross-provider caller drives every CLI identically.
		if (request.options() != null && !(request.options() instanceof GrokAgentOptions)) {
			if (request.options().getModel() != null) {
				builder.model(request.options().getModel());
			}
			// Portable effort values (low/medium/high) are all valid --reasoning-effort
			// values, so this is a direct passthrough.
			if (request.options().getEffort() != null) {
				builder.reasoningEffort(request.options().getEffort());
			}
			if (request.options().getTimeout() != null) {
				builder.timeout(request.options().getTimeout());
			}
			if (request.options().getMaxTurns() != null) {
				builder.maxTurns(request.options().getMaxTurns());
			}
			if (request.options().getJsonSchema() != null && !request.options().getJsonSchema().isEmpty()) {
				builder.jsonSchema(request.options().getJsonSchema());
			}
			if (request.options().isAutoApprove()) {
				builder.permissionMode(PermissionMode.BYPASS_PERMISSIONS);
			}
		}

		if (request.options() instanceof GrokAgentOptions requestOptions) {
			if (requestOptions.getModel() != null) {
				builder.model(requestOptions.getModel());
			}
			if (requestOptions.getReasoningEffort() != null) {
				builder.reasoningEffort(requestOptions.getReasoningEffort());
			}
			if (requestOptions.getTimeout() != null) {
				builder.timeout(requestOptions.getTimeout());
			}
			if (requestOptions.getPermissionMode() != null) {
				builder.permissionMode(requestOptions.getPermissionMode());
			}
			if (requestOptions.getMaxTurns() != null) {
				builder.maxTurns(requestOptions.getMaxTurns());
			}
			if (!requestOptions.getJsonSchema().isEmpty()) {
				builder.jsonSchema(requestOptions.getJsonSchema());
			}
			if (!requestOptions.getAllowedTools().isEmpty()) {
				builder.allowedTools(requestOptions.getAllowedTools());
			}
			if (!requestOptions.getDisallowedTools().isEmpty()) {
				builder.disallowedTools(requestOptions.getDisallowedTools());
			}
			builder.disableWebSearch(requestOptions.isDisableWebSearch());
		}

		return builder.build();
	}

	private ExecuteOptions toExecuteOptions(GrokAgentOptions options, AgentTaskRequest request) {
		ExecuteOptions.Builder builder = ExecuteOptions.builder()
			.model(options.getModel())
			.reasoningEffort(options.getReasoningEffort())
			.permissionMode(options.getPermissionMode())
			.maxTurns(options.getMaxTurns())
			.allowedTools(options.getAllowedTools())
			.disallowedTools(options.getDisallowedTools())
			.disableWebSearch(options.isDisableWebSearch())
			.executablePath(options.getExecutablePath());

		if (options.getTimeout() != null) {
			builder.timeout(options.getTimeout());
		}
		if (request.workingDirectory() != null) {
			builder.workingDirectory(request.workingDirectory());
		}
		// Grok takes the schema inline rather than as a file path, so unlike Codex there
		// is
		// no temp file to write and clean up.
		if (!options.getJsonSchema().isEmpty()) {
			builder.jsonSchema(serializeSchema(options.getJsonSchema()));
		}
		return builder.build();
	}

	private static String serializeSchema(Map<String, Object> jsonSchema) {
		try {
			return MAPPER.writeValueAsString(jsonSchema);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("jsonSchema could not be serialized: " + ex.getMessage(), ex);
		}
	}

	private GrokPhaseCapture parseCapture(ExecuteResult result, String prompt) throws IOException {
		String rawOutput = result.getRawOutput() != null ? result.getRawOutput() : "";
		try (BufferedReader reader = new BufferedReader(new StringReader(rawOutput))) {
			return GrokSessionParser.parse(reader, UUID.randomUUID().toString(), prompt);
		}
	}

	private AgentResponse toAgentResponse(ExecuteResult result, GrokPhaseCapture capture) {
		String finishReason = result.isSuccessful() ? "SUCCESS" : "ERROR";
		String model = (result.getModel() != null) ? result.getModel() : "grok-default";
		String sessionId = (result.getSessionId() != null) ? result.getSessionId() : "";

		Map<String, Object> providerFields = new LinkedHashMap<>();
		providerFields.put("exitCode", result.getExitCode());
		providerFields.put("successful", result.isSuccessful());
		providerFields.put("structured", result.isStructured());
		providerFields.put("stopReason", (result.getStopReason() != null) ? result.getStopReason() : "");
		// Grok is the only CLI in this family that reports a real per-run cost, so it is
		// surfaced verbatim rather than reconstructed from a price table.
		providerFields.put("costUsd", result.getTotalCostUsd());
		providerFields.put("inputTokens", result.getInputTokens());
		providerFields.put("outputTokens", result.getOutputTokens());
		providerFields.put("thinkingTokens", result.getReasoningTokens());
		providerFields.put("cacheReadTokens", result.getCacheReadInputTokens());
		providerFields.put("cacheCreationTokens", result.getCacheCreationInputTokens());
		providerFields.put("totalTokens", result.getTotalTokens());
		providerFields.put("numTurns", result.getNumTurns());
		AgentGeneration generation = new AgentGeneration(result.getText(),
				new AgentGenerationMetadata(finishReason, Map.copyOf(providerFields)));
		providerFields.put("phaseCapture", capture);
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model(model)
			.duration(result.getDuration())
			.sessionId(sessionId)
			.providerFields(Map.copyOf(providerFields))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private AgentResponse toErrorResponse(Exception ex) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName();
		AgentGeneration generation = new AgentGeneration(message,
				new AgentGenerationMetadata("ERROR", Map.of("error", message)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model("grok-default")
			.duration(Duration.ZERO)
			.providerFields(Map.of("error", message, "successful", false))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

}
