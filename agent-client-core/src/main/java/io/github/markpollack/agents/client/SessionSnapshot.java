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

public record SessionSnapshot(String sessionId, SessionState state, Optional<ProviderReference> providerReference,
		Optional<String> activeExecutionId, Optional<Reason> reason, List<String> diagnostics) {

	public SessionSnapshot {
		diagnostics = List.copyOf(diagnostics);
	}

	/**
	 * The pre-diagnostics shape; provider recording diagnostics appear once the
	 * connection has closed.
	 */
	public SessionSnapshot(String sessionId, SessionState state, Optional<ProviderReference> providerReference,
			Optional<String> activeExecutionId, Optional<Reason> reason) {
		this(sessionId, state, providerReference, activeExecutionId, reason, List.of());
	}
}
