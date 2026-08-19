/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk.exceptions;

/**
 * Runtime exception for Amp CLI SDK operations. All Amp SDK exceptions are runtime
 * exceptions following Spring AI Agents patterns.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class AmpSDKException extends RuntimeException {

	public AmpSDKException(String message) {
		super(message);
	}

	public AmpSDKException(String message, Throwable cause) {
		super(message, cause);
	}

	public AmpSDKException(Throwable cause) {
		super(cause);
	}

}
