/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

import java.time.Duration;
import java.util.Map;

/**
 * Minimal {@link AgentOptions} implementation that carries portable MCP definitions for
 * integration testing. All other options return defaults/nulls since the model under test
 * supplies its own configuration.
 *
 * @author Spring AI Community
 * @since 0.10.0
 */
public final class PortableMcpAgentOptions implements AgentOptions {

	private final Map<String, McpServerDefinition> mcpDefinitions;

	public PortableMcpAgentOptions(Map<String, McpServerDefinition> mcpDefinitions) {
		this.mcpDefinitions = mcpDefinitions;
	}

	@Override
	public String getWorkingDirectory() {
		return null;
	}

	@Override
	public Duration getTimeout() {
		return null;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return Map.of();
	}

	@Override
	public String getModel() {
		return null;
	}

	@Override
	public Map<String, Object> getExtras() {
		return Map.of();
	}

	@Override
	public Map<String, McpServerDefinition> getMcpServerDefinitions() {
		return this.mcpDefinitions;
	}

}
