/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.github.markpollack.agents.model.AgentOptions;

/**
 * Client-layer request type for agent execution flows with advisor support. Provides a
 * context map for advisors to share data across the execution chain.
 *
 * <p>
 * Follows the Spring AI ChatClientRequest pattern for consistency with the Spring AI
 * ecosystem.
 *
 * @param goal the goal to execute
 * @param workingDirectory the working directory for execution
 * @param options the agent configuration options
 * @param context mutable context map for advisors (judge parameters, metrics, etc.)
 * @author Mark Pollack
 * @since 0.1.0
 */
public record AgentClientRequest(Goal goal, Path workingDirectory, AgentOptions options, Map<String, Object> context) {

	/**
	 * Convenience constructor with empty context map.
	 * @param goal the goal to execute
	 * @param workingDirectory the working directory for execution
	 * @param options the agent configuration options
	 */
	public AgentClientRequest(Goal goal, Path workingDirectory, AgentOptions options) {
		this(goal, workingDirectory, options, new HashMap<>());
	}

}
