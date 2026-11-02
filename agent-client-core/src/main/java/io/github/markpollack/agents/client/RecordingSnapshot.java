/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record RecordingSnapshot(String executionId, long revision, Instant observedAt, Optional<String> decisionId,
		CaptureReceipt evidence, TerminalDelivery delivery, List<DeliveryAttempt> attempts) {
	public RecordingSnapshot {
		attempts = List.copyOf(attempts);
	}
}
