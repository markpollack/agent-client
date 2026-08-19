/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.exceptions;

/**
 * Exception thrown when the Gemini CLI is not found or not accessible.
 */
public class CLINotFoundException extends GeminiSDKException {

	public CLINotFoundException(String message) {
		super(message);
	}

	public CLINotFoundException(String message, Throwable cause) {
		super(message, cause);
	}

}