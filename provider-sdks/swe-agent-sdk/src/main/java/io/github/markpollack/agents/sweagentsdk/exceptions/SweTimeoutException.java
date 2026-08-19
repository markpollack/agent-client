/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagentsdk.exceptions;

import java.time.Duration;

/**
 * Exception thrown when an operation times out.
 *
 * <p>
 * This exception provides information about timeout events, including the elapsed time
 * and timeout duration.
 * </p>
 */
public class SweTimeoutException extends SweSDKException {

	private final Duration elapsedTime;

	private final Duration timeoutDuration;

	private final String operation;

	/**
	 * Creates a new SweTimeoutException.
	 * @param operation the operation that timed out
	 * @param elapsedTime the time that elapsed before timeout
	 * @param timeoutDuration the configured timeout duration
	 */
	public SweTimeoutException(String operation, Duration elapsedTime, Duration timeoutDuration) {
		super(formatMessage(operation, elapsedTime, timeoutDuration));
		this.operation = operation;
		this.elapsedTime = elapsedTime;
		this.timeoutDuration = timeoutDuration;
	}

	/**
	 * Creates a new SweTimeoutException with a cause.
	 * @param operation the operation that timed out
	 * @param elapsedTime the time that elapsed before timeout
	 * @param timeoutDuration the configured timeout duration
	 * @param cause the underlying cause
	 */
	public SweTimeoutException(String operation, Duration elapsedTime, Duration timeoutDuration, Throwable cause) {
		super(formatMessage(operation, elapsedTime, timeoutDuration), cause);
		this.operation = operation;
		this.elapsedTime = elapsedTime;
		this.timeoutDuration = timeoutDuration;
	}

	/**
	 * Gets the elapsed time before timeout.
	 * @return the elapsed time
	 */
	public Duration getElapsedTime() {
		return elapsedTime;
	}

	/**
	 * Gets the configured timeout duration.
	 * @return the timeout duration
	 */
	public Duration getTimeoutDuration() {
		return timeoutDuration;
	}

	/**
	 * Gets the operation that timed out.
	 * @return the operation description
	 */
	public String getOperation() {
		return operation;
	}

	/**
	 * Formats the exception message with timeout details.
	 */
	private static String formatMessage(String operation, Duration elapsedTime, Duration timeoutDuration) {
		return String.format("Operation '%s' timed out after %s (timeout: %s)", operation, elapsedTime,
				timeoutDuration);
	}

}