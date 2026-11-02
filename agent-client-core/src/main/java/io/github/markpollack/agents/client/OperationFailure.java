/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

public enum OperationFailure {

	BUSY, CLOSED, UNSUPPORTED, INVALID_INPUT, CLEANUP_UNKNOWN, IDENTITY_CONFLICT, SETUP_FAILED, SETUP_TIMEOUT,
	INTERRUPTED, SELF_CLOSE

}
