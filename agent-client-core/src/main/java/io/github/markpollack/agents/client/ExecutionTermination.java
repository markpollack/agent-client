/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public record ExecutionTermination(String executionId, String sessionId, Instant observedAt,
		Optional<Instant> settledAt, boolean cleanupConfirmed, List<String> unconfirmedResources,
		boolean providerTurnDispatched, boolean reconnected, SessionState sessionState, Optional<Reason> stopReason) {
	public ExecutionTermination {
		unconfirmedResources = List.copyOf(unconfirmedResources);
	}
}
