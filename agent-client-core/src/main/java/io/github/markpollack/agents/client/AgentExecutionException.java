/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

/** A failed accepted execution; each observation creates a fresh exception. */
public final class AgentExecutionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ExecutionSnapshot execution;

	private final FailureInfo failure;

	public AgentExecutionException(String message, Throwable cause, ExecutionSnapshot execution, FailureInfo failure) {
		super(message, cause);
		this.execution = execution;
		this.failure = failure;
	}

	public ExecutionSnapshot execution() {
		return this.execution;
	}

	public FailureInfo failure() {
		return this.failure;
	}

}
