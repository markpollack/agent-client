/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

/**
 * @deprecated Use {@link AgentApi} instead. This interface will be removed in a future
 * release. {@code AgentModel} was misleading — implementations wrap CLI agent runtimes,
 * not AI models.
 * @author Mark Pollack
 * @since 0.1.0
 * @see AgentApi
 */
@Deprecated(since = "0.16.0", forRemoval = true)
@FunctionalInterface
public interface AgentModel extends AgentApi {

}