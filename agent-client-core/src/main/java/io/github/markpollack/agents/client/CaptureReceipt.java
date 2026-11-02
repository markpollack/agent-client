/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.*;

/**
 * Frozen available evidence; recording() may later return a new snapshot, never mutate
 * this one.
 */
public record CaptureReceipt(String executionId, String clientSessionId, Optional<String> providerSessionId,
		ReportedCount turns, ReportedCost cost, TokenCounts tokens, ProviderObservation provider,
		CaptureAcquisition acquisition, String route, String fidelity, Optional<CaptureRecordReference> fullRecord,
		List<String> warnings) {
	public CaptureReceipt {
		warnings = List.copyOf(warnings);
	}
}
