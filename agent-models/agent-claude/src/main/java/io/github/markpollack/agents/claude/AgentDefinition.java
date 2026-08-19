/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.util.List;

/**
 * Definition for a programmatic subagent. Allows defining agents inline without
 * filesystem dependencies.
 *
 * @param description agent description
 * @param prompt the system prompt for this agent
 * @param tools allowed tools for this agent (null means inherit from parent)
 * @param model model to use: "sonnet", "opus", "haiku", or "inherit" (null means inherit)
 * @author Mark Pollack
 * @since 1.1.0
 */
public record AgentDefinition(String description, String prompt, List<String> tools, String model) {

	/**
	 * Creates an agent definition with minimal parameters.
	 * @param description agent description
	 * @param prompt the system prompt
	 */
	public AgentDefinition(String description, String prompt) {
		this(description, prompt, null, null);
	}

	/**
	 * Creates an agent definition with tools.
	 * @param description agent description
	 * @param prompt the system prompt
	 * @param tools allowed tools
	 */
	public AgentDefinition(String description, String prompt, List<String> tools) {
		this(description, prompt, tools, null);
	}

}
