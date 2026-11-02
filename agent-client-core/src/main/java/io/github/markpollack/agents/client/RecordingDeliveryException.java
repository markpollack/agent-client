/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

/** Classification supplied by a recorder; never an execution failure. */
public final class RecordingDeliveryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final boolean retryable;

	private final boolean acceptanceUnknown;

	private final FailureCode code;

	public RecordingDeliveryException(FailureCode code, String message, Throwable cause, boolean retryable,
			boolean acceptanceUnknown) {
		super(message, cause);
		if (code != FailureCode.RECORDING_FAILED && code != FailureCode.RECORDING_CONFLICT
				&& code != FailureCode.PERSISTENCE_FAILED)
			throw new IllegalArgumentException("Not a recording failure code");
		this.code = code;
		this.retryable = retryable;
		this.acceptanceUnknown = acceptanceUnknown;
	}

	public FailureCode code() {
		return this.code;
	}

	public boolean retryable() {
		return this.retryable;
	}

	public boolean acceptanceUnknown() {
		return this.acceptanceUnknown;
	}

}
