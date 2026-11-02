/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.Optional;

/** All members optional; no application run is invented by Client. */
public record ApplicationCorrelation(Optional<String> experimentId, Optional<String> experimentRunId,
		Optional<String> experimentInvocationId, Optional<String> workflowRunId, Optional<String> workflowInvocationId,
		Optional<String> workflowAttemptId) {
	public static ApplicationCorrelation empty() {
		return new ApplicationCorrelation(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.empty());
	}
}
