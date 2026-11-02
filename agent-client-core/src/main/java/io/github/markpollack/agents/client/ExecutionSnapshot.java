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

public record ExecutionSnapshot(String executionId, String sessionId, Future.State resultState,
		SettlementState settlement, boolean providerTurnDispatched, boolean reconnected, CleanupSnapshot cleanup,
		Optional<Reason> stopReason, CallbackSnapshot callbacks, Map<String, Object> metadata,
		List<String> diagnostics) {
	public ExecutionSnapshot {
		metadata = Map.copyOf(metadata);
		diagnostics = List.copyOf(diagnostics);
	}
}
