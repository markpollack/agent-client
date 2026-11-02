/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;

/** Close reached its original budget with resources still owned. */
public final class CleanupUnconfirmedException extends AgentOperationException {

	private static final long serialVersionUID = 1L;

	public CleanupUnconfirmedException(String message, CleanupHandle cleanup) {
		super(OperationFailure.CLEANUP_UNKNOWN, message, null, Optional.of(cleanup));
	}

}
