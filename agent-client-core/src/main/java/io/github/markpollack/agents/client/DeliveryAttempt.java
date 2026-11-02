/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Instant;
import java.util.Optional;

/**
 * Frozen attempt history; a late receipt is a separate observation, never an edited
 * attempt.
 */
public record DeliveryAttempt(String attemptId, int attemptNumber, Instant startedAt, Optional<Instant> endedAt,
		TerminalDelivery observation) {
}
