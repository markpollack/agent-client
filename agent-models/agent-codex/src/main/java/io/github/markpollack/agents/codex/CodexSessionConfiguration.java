/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/** Invocation-only translation. No files or persistent provider settings are written. */
final class CodexSessionConfiguration {

	private CodexSessionConfiguration() {
	}

	static ExecuteOptions create(Path directory, String model, Duration timeout, String name,
			McpServerDefinition definition, Set<String> approvedTools) {
		var overrides = new LinkedHashMap<String, String>();
		var environment = new LinkedHashMap<String, String>();
		if (definition != null) {
			if (name == null || !name.matches("[a-zA-Z0-9_-]+")) {
				throw new IllegalArgumentException("Invalid scoped MCP server name");
			}
			if (!(definition instanceof McpServerDefinition.HttpDefinition http)) {
				throw new UnsupportedOperationException("Codex conversations require Streamable HTTP MCP");
			}
			URI uri;
			try {
				uri = URI.create(http.url());
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid MCP endpoint URL");
			}
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
					|| uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null) {
				throw new IllegalArgumentException(
						"MCP endpoint must be HTTP(S), without credentials, query or fragment");
			}
			if (approvedTools.isEmpty()) {
				throw new IllegalArgumentException("Codex scoped MCP requires explicit approved tool names");
			}
			String prefix = "mcp_servers." + name;
			overrides.put(prefix + ".url", quote(http.url()));
			// Required servers make startup/handshake failures fatal, rather than losing
			// tools silently.
			overrides.put(prefix + ".required", "true");
			overrides.put(prefix + ".enabled", "true");
			for (String tool : approvedTools) {
				if (!tool.matches("[a-zA-Z0-9_-]+")) {
					throw new IllegalArgumentException("Invalid MCP tool name");
				}
				overrides.put(prefix + ".tools." + tool + ".approval_mode", quote("approve"));
			}
			for (var header : http.headers().entrySet()) {
				if (!header.getKey().equalsIgnoreCase("Authorization") || environment.size() > 0
						|| !header.getValue().startsWith("Bearer ")
						|| !header.getValue().substring(7).matches("[A-Za-z0-9._~+/=-]+")) {
					throw new UnsupportedOperationException(
							"Codex scoped HTTP supports only one Bearer Authorization header");
				}
				String variable = "AGENT_CLIENT_MCP_TOKEN_" + UUID.randomUUID().toString().replace("-", "");
				environment.put(variable, header.getValue().substring(7));
				overrides.put(prefix + ".bearer_token_env_var", quote(variable));
			}
		}
		return ExecuteOptions.builder()
			.model(model)
			.timeout(timeout)
			.workingDirectory(directory)
			.sandboxMode(SandboxMode.READ_ONLY)
			.jsonOutput(true)
			.configOverrides(overrides)
			.environment(environment)
			.build();
	}

	private static String quote(String value) {
		try {
			return new ObjectMapper().writeValueAsString(value);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Cannot encode MCP configuration");
		}
	}

}
