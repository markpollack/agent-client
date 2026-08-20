/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.exceptions;

/**
 * Unchecked failure from the Antigravity CLI SDK.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class AntigravitySDKException extends RuntimeException {

	public AntigravitySDKException(String message) {
		super(message);
	}

	public AntigravitySDKException(String message, Throwable cause) {
		super(message, cause);
	}

}
