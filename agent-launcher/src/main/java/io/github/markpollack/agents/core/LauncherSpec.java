/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * Combined specification for agent execution. Contains merged configuration from
 * AgentSpec defaults, run.yaml, and CLI arguments.
 *
 * @param agentSpec resolved agent specification with input defaults
 * @param inputs merged input values (defaults + run.yaml + CLI)
 * @param cwd working directory for execution
 * @param env execution environment settings
 * @author Mark Pollack
 * @since 1.1.0
 */
public record LauncherSpec(AgentSpec agentSpec, Map<String, Object> inputs, Path cwd, Map<String, Object> env) {
}