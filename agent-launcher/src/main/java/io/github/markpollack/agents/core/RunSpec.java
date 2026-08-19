/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.core;

import java.util.Map;

/**
 * Complete run configuration combining agent selection, sandbox environment, and task
 * parameters. This is the primary configuration object that benchmark programs generate
 * arrays of.
 *
 * @param agent which agent to run (matches AgentSpec.id)
 * @param inputs runtime input values for the task
 * @param workingDirectory sandbox working directory (null for current directory)
 * @param env execution environment variables and sandbox settings
 * @author Mark Pollack
 * @since 1.1.0
 */
public record RunSpec(String agent, Map<String, Object> inputs, String workingDirectory, Map<String, Object> env) {
}