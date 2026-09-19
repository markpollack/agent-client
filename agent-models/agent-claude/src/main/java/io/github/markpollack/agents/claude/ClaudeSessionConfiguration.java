/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/**
 * Immutable invocation options and a session-owned, fail-closed MCP configuration file.
 */
record ClaudeSessionConfiguration(CLIOptions initial, CLIOptions resumed, Path file) implements AutoCloseable {

	static ClaudeSessionConfiguration create(Path directory, String id, ClaudeAgentOptions defaults, Duration timeout,
			String name, McpServerDefinition definition) {
		return create(directory, id, defaults, timeout, name, definition, null);
	}

	static ClaudeSessionConfiguration create(Path directory, String id, ClaudeAgentOptions defaults, Duration timeout,
			String name, McpServerDefinition definition, Map<String, String> environment) {
		validateEndpoint(definition);
		if (defaults == null) {
			defaults = ClaudeAgentOptions.builder().yolo(false).build();
		}
		Path file = null;
		try (ClaudeAgentModel model = ClaudeAgentModel.builder().defaultOptions(defaults).timeout(timeout).build()) {
			var request = AgentTaskRequest.builder("", directory).build();
			CLIOptions.Builder builder = model.buildCLIOptionsBuilder(request);
			if (environment != null) {
				builder.env(Map.copyOf(environment));
			}
			CLIOptions base = builder.build();
			Map<String, McpServerConfig> servers = new LinkedHashMap<>(base.mcpServers());
			if (name != null) {
				if (servers.containsKey(name)) {
					throw new IllegalArgumentException(
							"Scoped MCP server name conflicts with a configured server: " + name);
				}
				servers.put(name, model.toClaudeMcpServerConfig(definition));
			}
			Map<String, String> args = new LinkedHashMap<>(base.extraArgs());
			if (!servers.isEmpty()) {
				if (servers.values().stream().anyMatch(McpServerConfig::isSdkServer)) {
					throw new UnsupportedOperationException(
							"Conversations require external MCP connection definitions");
				}
				file = Files.createTempFile("claude-session-mcp-", ".json");
				new ObjectMapper().writeValue(file.toFile(), Map.of("mcpServers", servers));
				args.put("mcp-config", file.toString());
			}
			var resumed = builder.mcpServers(Map.of()).extraArgs(args).resume(id).build();
			args.put("session-id", id);
			var initial = builder.extraArgs(args).resume(null).build();
			return new ClaudeSessionConfiguration(initial, resumed, file);
		}
		catch (IOException | RuntimeException ex) {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				}
				catch (IOException cleanup) {
					ex.addSuppressed(cleanup);
				}
			}
			throw new IllegalStateException("Cannot prepare Claude MCP conversation configuration", ex);
		}
	}

	private static void validateEndpoint(McpServerDefinition definition) {
		String url = definition instanceof McpServerDefinition.HttpDefinition http ? http.url()
				: definition instanceof McpServerDefinition.SseDefinition sse ? sse.url() : null;
		if (url != null) {
			java.net.URI uri = java.net.URI.create(url);
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
				throw new IllegalArgumentException("Claude MCP endpoints require an absolute HTTP or HTTPS URL");
			}
		}
	}

	@Override
	public void close() {
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Cannot remove Claude MCP configuration", ex);
			}
		}
	}
}
