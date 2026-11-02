/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;
import java.util.Objects;

/** A refusal or failed operation before an execution handle exists. */
public class AgentOperationException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final OperationFailure failure;

	private final Optional<CleanupHandle> cleanup;

	public AgentOperationException(OperationFailure failure, String message, Throwable cause,
			Optional<CleanupHandle> cleanup) {
		super(message, cause);
		this.failure = Objects.requireNonNull(failure, "failure");
		this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
	}

	public OperationFailure failure() {
		return this.failure;
	}

	public Optional<CleanupHandle> cleanup() {
		return this.cleanup;
	}

}
