/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.*;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/** Immutable scoped connection authority, installed only by an admitted execution. */
public record ConnectionBinding(Optional<McpSlot> mcp, Map<String, String> environment) {

	public ConnectionBinding {
		Objects.requireNonNull(mcp, "mcp");
		environment = Map.copyOf(environment);
	}

	public ConnectionBinding(String name, McpServerDefinition definition, Map<String, String> environment) {
		this(Optional.of(new McpSlot(name, definition)), environment);
	}

	public static ConnectionBinding withoutMcp(Map<String, String> environment) {
		return new ConnectionBinding(Optional.empty(), environment);
	}
	public record McpSlot(String name, McpServerDefinition definition) {
		public McpSlot {
			if (name == null || name.isBlank())
				throw new IllegalArgumentException("MCP slot name is required");
			Objects.requireNonNull(definition, "definition");
		}
	}

	void validateReplacement(ConnectionBinding replacement) {
		if (!environment.equals(replacement.environment())
				|| !mcp.map(McpSlot::name).equals(replacement.mcp().map(McpSlot::name)))
			throw new IllegalArgumentException("Refresh must preserve the original slot and full environment");
	}

	AgentOptions apply(AgentOptions options, boolean initial) {
		var definitions = new LinkedHashMap<>(options.getMcpServerDefinitions());
		mcp.ifPresent(slot -> {
			if (initial && definitions.containsKey(slot.name()))
				throw new IllegalArgumentException("Duplicate scoped MCP slot: " + slot.name());
			definitions.put(slot.name(), slot.definition());
		});
		return ExecutionOptions.copy(DefaultAgentOptions.builder()
			.from(options)
			.environmentVariables(environment)
			.mcpServerDefinitions(definitions)
			.build());
	}
}
