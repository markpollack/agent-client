/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.ExecuteResult;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.sandbox.Sandbox;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.markpollack.journal.codex.CodexPhaseCapture;
import io.github.markpollack.journal.codex.CodexSessionParser;

/**
 * Implementation of {@link AgentModel} for OpenAI Codex CLI-based agents.
 *
 * <p>
 * This adapter bridges Spring AI's agent abstraction with the Codex CLI, providing
 * autonomous development tasks through goal-driven task execution with advanced sandbox
 * and approval controls.
 * </p>
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class CodexAgentModel implements AgentModel {

	private static final Logger logger = LoggerFactory.getLogger(CodexAgentModel.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CodexClient codexClient;

	private final CodexAgentOptions defaultOptions;

	private final Sandbox sandbox;

	/**
	 * Create a new CodexAgentModel with the given client, options, and sandbox.
	 * @param codexClient the Codex CLI client
	 * @param defaultOptions default execution options
	 * @param sandbox the sandbox for secure command execution (may be null)
	 */
	public CodexAgentModel(CodexClient codexClient, CodexAgentOptions defaultOptions, Sandbox sandbox) {
		this.codexClient = codexClient;
		this.defaultOptions = defaultOptions != null ? defaultOptions : CodexAgentOptions.builder().build();
		this.sandbox = sandbox;

		// Set system property for executable path if provided
		if (this.defaultOptions.getExecutablePath() != null) {
			System.setProperty("CODEX_CLI_PATH", this.defaultOptions.getExecutablePath());
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
		logger.info("Executing Codex agent with goal: {}", goal);

		// Merge options
		CodexAgentOptions options = mergeOptions(request);

		// Bridge portable jsonSchema → temp file for Codex --output-schema
		Path jsonSchemaTempFile = null;
		try {
			if (options.getOutputSchema() == null && request.options() != null
					&& request.options().getJsonSchema() != null && !request.options().getJsonSchema().isEmpty()) {
				jsonSchemaTempFile = writeJsonSchemaToTempFile(request.options().getJsonSchema());
				options = CodexAgentOptions.builder()
					.model(options.getModel())
					.reasoningEffort(options.getReasoningEffort())
					.timeout(options.getTimeout())
					.fullAuto(options.isFullAuto())
					.sandboxMode(options.getSandboxMode())
					.approvalPolicy(options.getApprovalPolicy())
					.skipGitCheck(options.isSkipGitCheck())
					.dangerouslyBypassSandbox(options.isDangerouslyBypassSandbox())
					.additionalDirectories(options.getAdditionalDirectories())
					.executablePath(options.getExecutablePath())
					.outputSchema(jsonSchemaTempFile)
					.build();
			}

			// Convert to ExecuteOptions (includes working directory from request)
			ExecuteOptions executeOptions = toExecuteOptions(options, request);

			// Execute via SDK
			ExecuteResult result = codexClient.execute(goal, executeOptions);
			CodexPhaseCapture capture = parseCapture(result, goal);

			// Convert to AgentResponse
			return toAgentResponse(result, capture);
		}
		catch (Exception e) {
			logger.warn("Codex agent execution failed: {}", e.getMessage());
			return toErrorResponse(e);
		}
		finally {
			if (jsonSchemaTempFile != null) {
				try {
					Files.deleteIfExists(jsonSchemaTempFile);
				}
				catch (IOException ex) {
					logger.warn("Failed to delete temp schema file: {}", jsonSchemaTempFile, ex);
				}
			}
		}
	}

	private Path writeJsonSchemaToTempFile(Map<String, Object> jsonSchema) throws IOException {
		Path tempFile = Files.createTempFile("codex-schema-", ".json");
		MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), jsonSchema);
		logger.debug("Wrote portable jsonSchema to temp file: {}", tempFile);
		return tempFile;
	}

	@Override
	public boolean isAvailable() {
		try {
			return codexClient.isAvailable();
		}
		catch (Exception e) {
			logger.warn("Codex CLI availability check failed: {}", e.getMessage());
			return false;
		}
	}

	private CodexAgentOptions mergeOptions(AgentTaskRequest request) {
		// Start with defaults
		CodexAgentOptions.Builder builder = CodexAgentOptions.builder()
			.model(defaultOptions.getModel())
			.reasoningEffort(defaultOptions.getReasoningEffort())
			.timeout(defaultOptions.getTimeout())
			.fullAuto(defaultOptions.isFullAuto())
			.skipGitCheck(defaultOptions.isSkipGitCheck())
			.dangerouslyBypassSandbox(defaultOptions.isDangerouslyBypassSandbox())
			.additionalDirectories(defaultOptions.getAdditionalDirectories())
			.executablePath(defaultOptions.getExecutablePath());

		if (defaultOptions.getSandboxMode() != null) {
			builder.sandboxMode(defaultOptions.getSandboxMode());
		}

		if (defaultOptions.getApprovalPolicy() != null) {
			builder.approvalPolicy(defaultOptions.getApprovalPolicy());
		}

		// Portable option fallbacks (when request is not CodexAgentOptions)
		if (request.options() != null && !(request.options() instanceof CodexAgentOptions)) {
			builder.fullAuto(request.options().isAutoApprove());
			// Portable effort values (low/medium/high) are all valid Codex
			// model_reasoning_effort values — direct passthrough
			if (request.options().getEffort() != null) {
				builder.reasoningEffort(request.options().getEffort());
			}
		}

		// Override with request-specific options if present
		if (request.options() != null && request.options() instanceof CodexAgentOptions requestOptions) {
			if (requestOptions.getModel() != null) {
				builder.model(requestOptions.getModel());
			}
			if (requestOptions.getReasoningEffort() != null) {
				builder.reasoningEffort(requestOptions.getReasoningEffort());
			}
			if (requestOptions.getTimeout() != null) {
				builder.timeout(requestOptions.getTimeout());
			}
			if (requestOptions.getSandboxMode() != null) {
				builder.sandboxMode(requestOptions.getSandboxMode());
			}
			if (requestOptions.getApprovalPolicy() != null) {
				builder.approvalPolicy(requestOptions.getApprovalPolicy());
			}
			if (requestOptions.getOutputSchema() != null) {
				builder.outputSchema(requestOptions.getOutputSchema());
			}
			builder.fullAuto(requestOptions.isFullAuto());
			builder.skipGitCheck(requestOptions.isSkipGitCheck());
			builder.dangerouslyBypassSandbox(requestOptions.isDangerouslyBypassSandbox());
			if (!requestOptions.getAdditionalDirectories().isEmpty()) {
				builder.additionalDirectories(requestOptions.getAdditionalDirectories());
			}
		}

		return builder.build();
	}

	private ExecuteOptions toExecuteOptions(CodexAgentOptions options, AgentTaskRequest request) {
		ExecuteOptions.Builder builder = ExecuteOptions.builder()
			.model(options.getModel())
			.reasoningEffort(options.getReasoningEffort())
			.timeout(options.getTimeout())
			.fullAuto(options.isFullAuto())
			.skipGitCheck(options.isSkipGitCheck())
			.dangerouslyBypassSandbox(options.isDangerouslyBypassSandbox());
		builder.additionalDirectories(options.getAdditionalDirectories());

		if (!options.isFullAuto() && !options.isDangerouslyBypassSandbox()) {
			if (options.getSandboxMode() != null) {
				builder.sandboxMode(options.getSandboxMode());
			}
		}

		if (options.getOutputSchema() != null) {
			builder.outputSchema(options.getOutputSchema());
		}

		// Propagate working directory from request
		if (request.workingDirectory() != null) {
			builder.workingDirectory(request.workingDirectory());
		}

		return builder.build();
	}

	private AgentResponse toErrorResponse(Exception e) {
		AgentGeneration generation = new AgentGeneration(e.getMessage(),
				new AgentGenerationMetadata("ERROR", Map.of("error", e.getMessage())));
		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model("codex-default")
			.duration(Duration.ZERO)
			.providerFields(Map.of("error", e.getMessage()))
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private CodexPhaseCapture parseCapture(ExecuteResult result, String prompt) throws IOException {
		String rollout = String.join("\n", result.getRolloutLines());
		try (BufferedReader reader = new BufferedReader(new StringReader(rollout))) {
			return CodexSessionParser.parse(reader, UUID.randomUUID().toString(), prompt);
		}
	}

	private AgentResponse toAgentResponse(ExecuteResult result, CodexPhaseCapture capture) {
		String finishReason = result.isSuccessful() ? "SUCCESS" : "ERROR";

		// Create generation with output
		AgentGeneration generation = new AgentGeneration(result.getOutput(), new AgentGenerationMetadata(finishReason,
				Map.of("exitCode", result.getExitCode(), "model", result.getModel() != null ? result.getModel() : "",
						"sessionId", result.getSessionId() != null ? result.getSessionId() : "", "activityLog",
						result.getActivityLog() != null ? result.getActivityLog() : "")));

		// Create response metadata with sessionId
		Map<String, Object> providerFields = new LinkedHashMap<>();
		providerFields.put("exitCode", result.getExitCode());
		providerFields.put("successful", result.isSuccessful());
		providerFields.put("activityLog", result.getActivityLog() != null ? result.getActivityLog() : "");
		providerFields.put("phaseCapture", capture);

		AgentResponseMetadata metadata = AgentResponseMetadata.builder()
			.model(result.getModel() != null ? result.getModel() : "codex-default")
			.duration(result.getDuration())
			.sessionId(result.getSessionId() != null ? result.getSessionId() : "")
			.providerFields(Map.copyOf(providerFields))
			.build();

		return new AgentResponse(List.of(generation), metadata);
	}

}
