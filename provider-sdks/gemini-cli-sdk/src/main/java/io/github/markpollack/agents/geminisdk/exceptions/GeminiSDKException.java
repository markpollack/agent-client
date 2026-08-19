/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.exceptions;

/**
 * Base exception for all Gemini SDK related errors. Provides a consistent exception
 * hierarchy for the SDK.
 */
public class GeminiSDKException extends RuntimeException {

	public GeminiSDKException(String message) {
		super(message);
	}

	public GeminiSDKException(String message, Throwable cause) {
		super(message, cause);
	}

	public GeminiSDKException(Throwable cause) {
		super(cause);
	}

}