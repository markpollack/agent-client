/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/** Maps effective Claude and portable request options to CLI options. */
final class ClaudeCliOptionsMapper {

	private ClaudeCliOptionsMapper() {
	}

	static CLIOptions build(AgentTaskRequest request, ClaudeAgentOptions options, Duration timeout) {
		CLIOptions.Builder builder = CLIOptions.builder();
		applyEnvironment(builder, options, request);
		applyModel(builder, options, timeout);
		applyPermissions(builder, options, request);
		applyEffort(builder, options, request);
		applyTokens(builder, options);
		applySystemPrompt(builder, options);
		applyTools(builder, options);
		applySchema(builder, options, request);
		applyMcpServers(builder, options, request);
		applyLimits(builder, options, request);
		applyPromptSuffix(builder, options, request);
		return builder.build();
	}

	private static void applyEnvironment(CLIOptions.Builder builder, ClaudeAgentOptions options,
			AgentTaskRequest request) {
		Map<String, String> environment = new LinkedHashMap<>(options.getEnvironmentVariables());
		if (request.options() != null && request.options().getEnvironmentVariables() != null) {
			environment.putAll(request.options().getEnvironmentVariables());
		}
		builder.env(environment);
	}

	private static void applyModel(CLIOptions.Builder builder, ClaudeAgentOptions options, Duration timeout) {
		if (options.getTimeout() != null) {
			builder.timeout(options.getTimeout());
		}
		else if (timeout != null) {
			builder.timeout(timeout);
		}

		if (options.getModel() != null) {
			builder.model(options.getModel());
		}
	}

	private static void applyPermissions(CLIOptions.Builder builder, ClaudeAgentOptions options,
			AgentTaskRequest request) {
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
	}

	private static void applyEffort(CLIOptions.Builder builder, ClaudeAgentOptions options, AgentTaskRequest request) {
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
	}

	private static void applyTokens(CLIOptions.Builder builder, ClaudeAgentOptions options) {
		// Extended thinking
		if (options.getMaxThinkingTokens() != null) {
			builder.maxThinkingTokens(options.getMaxThinkingTokens());
		}

		// Max tokens
		if (options.getMaxTokens() != null) {
			builder.maxTokens(options.getMaxTokens());
		}
	}

	private static void applySystemPrompt(CLIOptions.Builder builder, ClaudeAgentOptions options) {
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
	}

	private static void applyTools(CLIOptions.Builder builder, ClaudeAgentOptions options) {
		// Tool filtering
		if (options.getAllowedTools() != null && !options.getAllowedTools().isEmpty()) {
			builder.allowedTools(options.getAllowedTools());
		}
		if (options.getDisallowedTools() != null && !options.getDisallowedTools().isEmpty()) {
			builder.disallowedTools(options.getDisallowedTools());
		}
	}

	private static void applySchema(CLIOptions.Builder builder, ClaudeAgentOptions options, AgentTaskRequest request) {
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
	}

	private static void applyMcpServers(CLIOptions.Builder builder, ClaudeAgentOptions options,
			AgentTaskRequest request) {
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
	}

	private static void applyLimits(CLIOptions.Builder builder, ClaudeAgentOptions options, AgentTaskRequest request) {
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
	}

	private static void applyPromptSuffix(CLIOptions.Builder builder, ClaudeAgentOptions options,
			AgentTaskRequest request) {
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
	}

	static McpServerConfig toClaudeMcpServerConfig(McpServerDefinition definition) {
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

}
