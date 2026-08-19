/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, map-backed implementation of {@link McpServerCatalog}.
 *
 * @author Spring AI Community
 * @since 0.10.0
 */
final class DefaultMcpServerCatalog implements McpServerCatalog {

	private final Map<String, McpServerDefinition> servers;

	DefaultMcpServerCatalog(Map<String, McpServerDefinition> servers) {
		this.servers = Map.copyOf(servers != null ? servers : Map.of());
	}

	@Override
	public Map<String, McpServerDefinition> getAll() {
		return this.servers;
	}

	@Override
	public Map<String, McpServerDefinition> resolve(Collection<String> names) {
		if (names == null || names.isEmpty()) {
			return Map.of();
		}
		Map<String, McpServerDefinition> resolved = new LinkedHashMap<>();
		for (String name : names) {
			McpServerDefinition definition = this.servers.get(name);
			if (definition == null) {
				throw new IllegalArgumentException(
						"MCP server '" + name + "' not found in catalog. Available: " + this.servers.keySet());
			}
			resolved.put(name, definition);
		}
		return Map.copyOf(resolved);
	}

	@Override
	public boolean contains(String name) {
		return this.servers.containsKey(name);
	}

}
