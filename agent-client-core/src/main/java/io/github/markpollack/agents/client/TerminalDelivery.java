/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Instant;

public sealed interface TerminalDelivery permits TerminalDelivery.NotConfigured, TerminalDelivery.Pending,
		TerminalDelivery.Processing, TerminalDelivery.Completed, TerminalDelivery.Failed, TerminalDelivery.Resubmitted {

	record NotConfigured() implements TerminalDelivery {
	}

	record Pending(Instant since) implements TerminalDelivery {
	}

	record Processing(String attemptId, int attemptNumber, Instant startedAt) implements TerminalDelivery {
	}

	record Completed(String bindingId, Instant acknowledgedAt, boolean lateAcknowledgment) implements TerminalDelivery {
	}

	record Failed(FailureInfo failure, boolean retryable, boolean acceptanceUnknown) implements TerminalDelivery {
	}

	record Resubmitted(String attemptId, int attemptNumber, Instant eligibleAt) implements TerminalDelivery {
	}

}
