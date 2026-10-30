/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude.autoconfigure;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/** Maps bound configuration properties to the Claude SDK's CLI options. */
final class ClaudePropertyOptionsMapper {

	private ClaudePropertyOptionsMapper() {
	}

	static void applyPermissions(CLIOptions.Builder builder, String permissionMode, boolean yolo) {
		// Permission mode: explicit setting takes precedence over yolo
		if (permissionMode != null && !permissionMode.isBlank()) {
			builder.permissionMode(PermissionMode.fromValue(permissionMode));
		}
		else if (yolo) {
			builder.permissionMode(PermissionMode.BYPASS_PERMISSIONS);
		}
	}

	static void applyGeneration(CLIOptions.Builder builder, Integer maxThinkingTokens, Integer maxTokens,
			String systemPrompt) {
		// Extended thinking
		if (maxThinkingTokens != null) {
			builder.maxThinkingTokens(maxThinkingTokens);
		}

		// Max tokens
		if (maxTokens != null) {
			builder.maxTokens(maxTokens);
		}

		// System prompt
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			builder.systemPrompt(systemPrompt);
		}
	}

	static void applyToolsAndSchema(CLIOptions.Builder builder, List<String> allowedTools, List<String> disallowedTools,
			Map<String, Object> jsonSchema) {
		// Tool filtering
		if (allowedTools != null && !allowedTools.isEmpty()) {
			builder.allowedTools(allowedTools);
		}
		if (disallowedTools != null && !disallowedTools.isEmpty()) {
			builder.disallowedTools(disallowedTools);
		}

		// Structured output
		if (jsonSchema != null && !jsonSchema.isEmpty()) {
			builder.jsonSchema(jsonSchema);
		}
	}

	static void applyBudgetAndPrompts(CLIOptions.Builder builder, Integer maxTurns, Double maxBudgetUsd,
			String fallbackModel, String appendSystemPrompt) {
		// Budget control
		if (maxTurns != null) {
			builder.maxTurns(maxTurns);
		}
		if (maxBudgetUsd != null) {
			builder.maxBudgetUsd(maxBudgetUsd);
		}

		// Fallback model
		if (fallbackModel != null && !fallbackModel.isBlank()) {
			builder.fallbackModel(fallbackModel);
		}

		// Append system prompt (uses preset mode)
		if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
			builder.appendSystemPrompt(appendSystemPrompt);
		}

		// ============================================================
		// Advanced options for full Python SDK parity
		// ============================================================
	}

	static void applyAdvancedSettings(CLIOptions.Builder builder, List<String> addDirs, String settings,
			String permissionPromptToolName) {
		// Additional context directories
		if (addDirs != null && !addDirs.isEmpty()) {
			builder.addDirs(addDirs.stream().map(Path::of).toList());
		}

		// Custom settings file
		if (settings != null && !settings.isBlank()) {
			builder.settings(settings);
		}

		// Permission prompt tool
		if (permissionPromptToolName != null && !permissionPromptToolName.isBlank()) {
			builder.permissionPromptToolName(permissionPromptToolName);
		}
	}

	static void applyExtraArgs(CLIOptions.Builder builder, String effort, Map<String, String> extraArgs) {
		// Extra CLI arguments — effort rides along as "--effort <level>" until the SDK
		// grows first-class support; an explicit extra-args "effort" entry wins
		Map<String, String> mergedExtraArgs = new java.util.LinkedHashMap<>();
		if (effort != null && !effort.isBlank()) {
			mergedExtraArgs.put("effort", effort);
		}
		if (extraArgs != null) {
			mergedExtraArgs.putAll(extraArgs);
		}
		if (!mergedExtraArgs.isEmpty()) {
			builder.extraArgs(mergedExtraArgs);
		}
	}

	static void applyEnvironment(CLIOptions.Builder builder, Map<String, String> env, Integer maxBufferSize,
			String user) {
		// Environment variables
		if (env != null && !env.isEmpty()) {
			builder.env(env);
		}

		// Buffer size protection
		if (maxBufferSize != null) {
			builder.maxBufferSize(maxBufferSize);
		}

		// Unix user
		if (user != null && !user.isBlank()) {
			builder.user(user);
		}
	}

}
