/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Observed provider facts, unaffected by Client failure/cancellation or recording
 * failure.
 */
public record ProviderObservation(Kind kind, Optional<String> nativeReason, OptionalInt observedExitCode) {
	public enum Kind {

		REPORTED_SUCCESS, REPORTED_FAILURE, STOPPED, NOT_OBSERVED, NOT_DISPATCHED

	}
}
