/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude.autoconfigure;

import java.nio.file.Path;
import java.util.Map;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/** Maps bound configuration properties to the Claude SDK's CLI options. */
final class ClaudePropertyOptionsMapper {

	private ClaudePropertyOptionsMapper() {
	}

	static CLIOptions build(ClaudeAgentProperties properties) {
		CLIOptions.Builder builder = CLIOptions.builder().model(properties.getModel()).timeout(properties.getTimeout());
		applyPermissions(builder, properties);
		applyGeneration(builder, properties);
		applyToolsAndSchema(builder, properties);
		applyBudgetAndPrompts(builder, properties);
		applyAdvancedSettings(builder, properties);
		applyExtraArgs(builder, properties);
		applyEnvironment(builder, properties);
		return builder.build();
	}

	private static void applyPermissions(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Permission mode: explicit setting takes precedence over yolo
		if (properties.getPermissionMode() != null && !properties.getPermissionMode().isBlank()) {
			builder.permissionMode(PermissionMode.fromValue(properties.getPermissionMode()));
		}
		else if (properties.isYolo()) {
			builder.permissionMode(PermissionMode.BYPASS_PERMISSIONS);
		}
	}

	private static void applyGeneration(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Extended thinking
		if (properties.getMaxThinkingTokens() != null) {
			builder.maxThinkingTokens(properties.getMaxThinkingTokens());
		}

		// Max tokens
		if (properties.getMaxTokens() != null) {
			builder.maxTokens(properties.getMaxTokens());
		}

		// System prompt
		if (properties.getSystemPrompt() != null && !properties.getSystemPrompt().isBlank()) {
			builder.systemPrompt(properties.getSystemPrompt());
		}
	}

	private static void applyToolsAndSchema(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Tool filtering
		if (properties.getAllowedTools() != null && !properties.getAllowedTools().isEmpty()) {
			builder.allowedTools(properties.getAllowedTools());
		}
		if (properties.getDisallowedTools() != null && !properties.getDisallowedTools().isEmpty()) {
			builder.disallowedTools(properties.getDisallowedTools());
		}

		// Structured output
		if (properties.getJsonSchema() != null && !properties.getJsonSchema().isEmpty()) {
			builder.jsonSchema(properties.getJsonSchema());
		}
	}

	private static void applyBudgetAndPrompts(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Budget control
		if (properties.getMaxTurns() != null) {
			builder.maxTurns(properties.getMaxTurns());
		}
		if (properties.getMaxBudgetUsd() != null) {
			builder.maxBudgetUsd(properties.getMaxBudgetUsd());
		}

		// Fallback model
		if (properties.getFallbackModel() != null && !properties.getFallbackModel().isBlank()) {
			builder.fallbackModel(properties.getFallbackModel());
		}

		// Append system prompt (uses preset mode)
		if (properties.getAppendSystemPrompt() != null && !properties.getAppendSystemPrompt().isBlank()) {
			builder.appendSystemPrompt(properties.getAppendSystemPrompt());
		}

		// ============================================================
		// Advanced options for full Python SDK parity
		// ============================================================
	}

	private static void applyAdvancedSettings(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Additional context directories
		if (properties.getAddDirs() != null && !properties.getAddDirs().isEmpty()) {
			builder.addDirs(properties.getAddDirs().stream().map(Path::of).toList());
		}

		// Custom settings file
		if (properties.getSettings() != null && !properties.getSettings().isBlank()) {
			builder.settings(properties.getSettings());
		}

		// Permission prompt tool
		if (properties.getPermissionPromptToolName() != null && !properties.getPermissionPromptToolName().isBlank()) {
			builder.permissionPromptToolName(properties.getPermissionPromptToolName());
		}
	}

	private static void applyExtraArgs(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Extra CLI arguments — effort rides along as "--effort <level>" until the SDK
		// grows first-class support; an explicit extra-args "effort" entry wins
		Map<String, String> mergedExtraArgs = new java.util.LinkedHashMap<>();
		if (properties.getEffort() != null && !properties.getEffort().isBlank()) {
			mergedExtraArgs.put("effort", properties.getEffort());
		}
		if (properties.getExtraArgs() != null) {
			mergedExtraArgs.putAll(properties.getExtraArgs());
		}
		if (!mergedExtraArgs.isEmpty()) {
			builder.extraArgs(mergedExtraArgs);
		}
	}

	private static void applyEnvironment(CLIOptions.Builder builder, ClaudeAgentProperties properties) {
		// Environment variables
		if (properties.getEnv() != null && !properties.getEnv().isEmpty()) {
			builder.env(properties.getEnv());
		}

		// Buffer size protection
		if (properties.getMaxBufferSize() != null) {
			builder.maxBufferSize(properties.getMaxBufferSize());
		}

		// Unix user
		if (properties.getUser() != null && !properties.getUser().isBlank()) {
			builder.user(properties.getUser());
		}
	}

}
