/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.antigravitysdk.AntigravityClient;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteResult;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter from the agent-client SPI to the Google Antigravity CLI ({@code agy}).
 *
 * <h2>Soft denial is reported, not hidden</h2>
 *
 * <p>
 * A headless Antigravity run whose tool calls were refused for want of approval still
 * exits 0 and still reports {@code SUCCESS}. This adapter surfaces that as
 * {@code softDenied} and {@code permissionNotices} in the response's provider fields, and
 * logs a warning, so a caller can tell a completed run from a completed-but-prevented
 * one. The finish reason stays SUCCESS because the run genuinely did complete; callers
 * who consider partial work a failure have the fact they need to say so.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class AntigravityAgentModel implements AgentModel {

	private static final Logger logger = LoggerFactory.getLogger(AntigravityAgentModel.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AntigravityClient antigravityClient;

	private final AntigravityAgentOptions defaultOptions;

	public AntigravityAgentModel(AntigravityClient antigravityClient, AntigravityAgentOptions defaultOptions) {
		this.antigravityClient = antigravityClient;
		this.defaultOptions = (defaultOptions != null) ? defaultOptions : AntigravityAgentOptions.builder().build();
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		String goal = request.goal();
		if (request.options() != null && request.options().getSystemInstructions() != null
				&& !request.options().getSystemInstructions().isEmpty()) {
			// agy has no system-prompt flag, so portable systemInstructions are
			// prepended.
			goal = request.options().getSystemInstructions() + "\n\n" + goal;
		}
		logger.info("Executing Antigravity agent with goal of {} characters", goal.length());

		try {
			ExecuteResult result = this.antigravityClient.execute(goal,
					toExecuteOptions(mergeOptions(request), request));
			return toAgentResponse(result);
		}
		catch (Exception ex) {
			logger.warn("Antigravity agent execution failed: {}", ex.getMessage());
			return toErrorResponse(ex);
		}
	}

	@Override
	public boolean isAvailable() {
		try {
			return this.antigravityClient.isAvailable();
		}
		catch (Exception ex) {
			logger.warn("Antigravity CLI availability check failed: {}", ex.getMessage());
			return false;
		}
	}

	private AntigravityAgentOptions mergeOptions(AgentTaskRequest request) {
		AntigravityAgentOptions.Builder builder = AntigravityAgentOptions.builder()
			.model(this.defaultOptions.getModel())
			.reasoningEffort(this.defaultOptions.getReasoningEffort())
			.timeout(this.defaultOptions.getTimeout())
			.dangerouslySkipPermissions(this.defaultOptions.isDangerouslySkipPermissions())
			.executionMode(this.defaultOptions.getExecutionMode())
			.additionalDirectories(this.defaultOptions.getAdditionalDirectories())
			.maxTurns(this.defaultOptions.getMaxTurns())
			.jsonSchema(this.defaultOptions.getJsonSchema())
			.executablePath(this.defaultOptions.getExecutablePath());

		// Portable fallbacks — the path a cross-provider caller takes.
		if (request.options() != null && !(request.options() instanceof AntigravityAgentOptions)) {
			if (request.options().getModel() != null) {
				builder.model(request.options().getModel());
			}
			// Portable low/medium/high are exactly agy's --effort values.
			if (request.options().getEffort() != null) {
				builder.reasoningEffort(request.options().getEffort());
			}
			if (request.options().getTimeout() != null) {
				builder.timeout(request.options().getTimeout());
			}
			if (!request.options().getJsonSchema().isEmpty()) {
				builder.jsonSchema(request.options().getJsonSchema());
			}
			builder.dangerouslySkipPermissions(request.options().isAutoApprove());
		}

		if (request.options() instanceof AntigravityAgentOptions requestOptions) {
			if (requestOptions.getModel() != null) {
				builder.model(requestOptions.getModel());
			}
			if (requestOptions.getReasoningEffort() != null) {
				builder.reasoningEffort(requestOptions.getReasoningEffort());
			}
			if (requestOptions.getTimeout() != null) {
				builder.timeout(requestOptions.getTimeout());
			}
			if (requestOptions.getExecutionMode() != null) {
				builder.executionMode(requestOptions.getExecutionMode());
			}
			if (!requestOptions.getAdditionalDirectories().isEmpty()) {
				builder.additionalDirectories(requestOptions.getAdditionalDirectories());
			}
			if (!requestOptions.getJsonSchema().isEmpty()) {
				builder.jsonSchema(requestOptions.getJsonSchema());
			}
			builder.dangerouslySkipPermissions(requestOptions.isDangerouslySkipPermissions());
		}

		return builder.build();
	}

	private ExecuteOptions toExecuteOptions(AntigravityAgentOptions options, AgentTaskRequest request) {
		ExecuteOptions.Builder builder = ExecuteOptions.builder()
			.model(options.getModel())
			.effort(options.getReasoningEffort())
			.dangerouslySkipPermissions(options.isDangerouslySkipPermissions())
			.mode(options.getExecutionMode())
			.additionalDirectories(options.getAdditionalDirectories())
			.executablePath(options.getExecutablePath());

		if (options.getTimeout() != null) {
			builder.timeout(options.getTimeout());
		}
		if (request.workingDirectory() != null) {
			builder.workingDirectory(request.workingDirectory());
		}
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

	private AgentResponse toAgentResponse(ExecuteResult result) {
		String finishReason = result.isSuccessful() ? "SUCCESS" : "ERROR";
		String conversationId = (result.getConversationId() != null) ? result.getConversationId() : "";

		Map<String, Object> providerFields = new LinkedHashMap<>();
		providerFields.put("exitCode", result.getExitCode());
		providerFields.put("successful", result.isSuccessful());
		providerFields.put("structured", result.isStructured());
		// The CLI's own verdict, kept verbatim next to ours. They disagree often enough
		// that recording only one of them would be misleading either way.
		providerFields.put("status", result.getStatus());
		providerFields.put("reportedSuccessful", result.isReportedSuccessful());
		providerFields.put("error", (result.getError() != null) ? result.getError() : "");
		providerFields.put("softDenied", result.isSoftDenied());
		providerFields.put("permissionNotices", result.getPermissionNotices());
		providerFields.put("inputTokens", result.getInputTokens());
		providerFields.put("outputTokens", result.getOutputTokens());
		providerFields.put("thinkingTokens", result.getThinkingTokens());
		providerFields.put("cacheReadTokens", result.getCacheReadTokens());
		providerFields.put("totalTokens", result.getTotalTokens());
		providerFields.put("numTurns", result.getNumTurns());

		AgentGeneration generation = new AgentGeneration(result.getResponse(),
				new AgentGenerationMetadata(finishReason, Map.copyOf(providerFields)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model((this.defaultOptions.getModel() != null) ? this.defaultOptions.getModel() : "antigravity-default")
			.duration(result.getDuration())
			.sessionId(conversationId)
			.providerFields(Map.copyOf(providerFields))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private AgentResponse toErrorResponse(Exception ex) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getName();
		AgentGeneration generation = new AgentGeneration(message,
				new AgentGenerationMetadata("ERROR", Map.of("error", message)));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model("antigravity-default")
			.duration(Duration.ZERO)
			.providerFields(Map.of("error", message, "successful", false))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

}
