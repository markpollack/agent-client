/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagentsdk.exceptions;

/**
 * Base exception for all SWE Agent SDK operations.
 *
 * <p>
 * This is the parent class for all exceptions thrown by the SWE Agent SDK. It provides a
 * consistent exception hierarchy for error handling.
 * </p>
 */
public class SweSDKException extends RuntimeException {

	/**
	 * Creates a new SweSDKException with the specified message.
	 * @param message the detail message
	 */
	public SweSDKException(String message) {
		super(message);
	}

	/**
	 * Creates a new SweSDKException with the specified message and cause.
	 * @param message the detail message
	 * @param cause the underlying cause
	 */
	public SweSDKException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates a new SweSDKException with the specified cause.
	 * @param cause the underlying cause
	 */
	public SweSDKException(Throwable cause) {
		super(cause);
	}

}