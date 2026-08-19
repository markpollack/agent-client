/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.exceptions;

/**
 * Runtime exception for Codex CLI SDK operations. All checked exceptions are wrapped
 * immediately at the call site to maintain a runtime-only exception design.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class CodexSDKException extends RuntimeException {

	public CodexSDKException(String message) {
		super(message);
	}

	public CodexSDKException(String message, Throwable cause) {
		super(message, cause);
	}

}
