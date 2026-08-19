/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.core;

import java.util.Map;

/**
 * Immutable specification defining what an agent does. Contains input definitions only.
 * Prompts are hardcoded in agent implementations as black boxes.
 *
 * @param id unique agent identifier (e.g., "hello-world", "coverage")
 * @param version agent version
 * @param inputs input definitions with types and defaults
 * @author Mark Pollack
 * @since 1.1.0
 */
public record AgentSpec(String id, String version, Map<String, InputDef> inputs) {

	/**
	 * Input definition with type information and defaults.
	 *
	 * @param type input type ("string", "integer", "boolean")
	 * @param defaultValue default value if not provided
	 * @param required whether input is required
	 */
	public record InputDef(String type, Object defaultValue, boolean required) {
	}

}