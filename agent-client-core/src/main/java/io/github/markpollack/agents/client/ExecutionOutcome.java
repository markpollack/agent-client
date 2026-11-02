/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

public sealed interface ExecutionOutcome permits ExecutionOutcome.Success, ExecutionOutcome.Stopped,
		ExecutionOutcome.Cancelled, ExecutionOutcome.Failed {

	record Success() implements ExecutionOutcome {
	}

	record Stopped(Reason reason) implements ExecutionOutcome {
	}

	record Cancelled(Reason reason) implements ExecutionOutcome {
	}

	record Failed(FailureInfo failure) implements ExecutionOutcome {
	}

}
