/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.exceptions;

import java.time.Duration;

/**
 * Exception thrown when a Gemini CLI operation times out.
 */
public class TimeoutException extends GeminiSDKException {

	private final Duration timeout;

	public TimeoutException(String message, Duration timeout) {
		super(String.format("%s (Timeout: %s)", message, timeout));
		this.timeout = timeout;
	}

	public TimeoutException(String message, Duration timeout, Throwable cause) {
		super(String.format("%s (Timeout: %s)", message, timeout), cause);
		this.timeout = timeout;
	}

	public Duration getTimeout() {
		return timeout;
	}

}