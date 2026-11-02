/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

/** Immutable revision, published by one volatile write after each locked transition. */
record ExecutionState(ExecutionDecision decision, ExecutionFailure failure, AgentClientResponse response,
		ExecutionTermination termination, ExecutionSnapshot snapshot) {
}
