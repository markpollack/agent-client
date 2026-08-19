/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

/**
 * Enumeration of message types in the Gemini SDK.
 */
public enum MessageType {

	/**
	 * User input message
	 */
	USER,

	/**
	 * Assistant response message
	 */
	ASSISTANT,

	/**
	 * System message
	 */
	SYSTEM,

	/**
	 * Error message
	 */
	ERROR

}