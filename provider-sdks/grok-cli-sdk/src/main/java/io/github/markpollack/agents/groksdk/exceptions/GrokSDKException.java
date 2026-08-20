/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.exceptions;

/**
 * Unchecked failure from the Grok CLI SDK.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class GrokSDKException extends RuntimeException {

	public GrokSDKException(String message) {
		super(message);
	}

	public GrokSDKException(String message, Throwable cause) {
		super(message, cause);
	}

}
