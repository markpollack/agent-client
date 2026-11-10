/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

import java.util.Map;
import java.util.TreeSet;

import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Refuses MCP servers for an agent that Client cannot pass them to. An {@link AgentModel}
 * for such an agent calls {@link #refuse(String, AgentOptions)} before it
 * launches anything, so a configured server is reported instead of silently dropped.
 *
 * @since 0.32.1
 */
public final class UnsupportedMcpServers {

	private UnsupportedMcpServers() {
	}

	/**
	 * Throws when the options carry any MCP server definitions.
	 * @param agent the agent's name, used in the message
	 * @param options the request's options; {@code null} carries no servers
	 * @throws UnsupportedOperationException naming the configured servers
	 */
	public static void refuse(String agent, AgentOptions options) {
		Map<String, McpServerDefinition> servers = (options != null) ? options.getMcpServerDefinitions() : null;
		if (servers == null || servers.isEmpty()) {
			return;
		}
		throw new UnsupportedOperationException("MCP servers " + new TreeSet<>(servers.keySet())
				+ " were configured, but Client cannot pass MCP servers to " + agent + "; the agent was not launched");
	}

}
