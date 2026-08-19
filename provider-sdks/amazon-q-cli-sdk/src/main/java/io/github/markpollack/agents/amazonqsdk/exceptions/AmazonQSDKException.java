/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonqsdk.exceptions;

/**
 * Runtime exception wrapper for Amazon Q CLI SDK errors.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class AmazonQSDKException extends RuntimeException {

	public AmazonQSDKException(String message) {
		super(message);
	}

	public AmazonQSDKException(String message, Throwable cause) {
		super(message, cause);
	}

}
