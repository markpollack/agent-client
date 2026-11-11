/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One observation of an execution's secondary recording. {@link #nativeRecording()} is
 * empty until the recorder has prepared the initial binding, and for a recorder that does
 * not report it.
 *
 * <p>
 * {@link #nativeRecording()} is set when the binding is prepared, before delivery, so
 * {@code ATTACHED} can sit beside a {@link TerminalDelivery.Failed} delivery: the sources
 * were qualified, but the binding never reached the archive. A caller that needs native
 * sources in the archive checks both an {@code ATTACHED} outcome and a
 * {@link TerminalDelivery.Completed} delivery. {@code Completed} alone also covers
 * evidence-only outcomes.
 */
public record RecordingSnapshot(String executionId, long revision, Instant observedAt, Optional<String> decisionId,
		CaptureReceipt evidence, TerminalDelivery delivery, List<DeliveryAttempt> attempts,
		Optional<NativeRecording> nativeRecording) {
	public RecordingSnapshot {
		attempts = List.copyOf(attempts);
		java.util.Objects.requireNonNull(nativeRecording, "nativeRecording");
	}

	public RecordingSnapshot(String executionId, long revision, Instant observedAt, Optional<String> decisionId,
			CaptureReceipt evidence, TerminalDelivery delivery, List<DeliveryAttempt> attempts) {
		this(executionId, revision, observedAt, decisionId, evidence, delivery, attempts, Optional.empty());
	}
}
