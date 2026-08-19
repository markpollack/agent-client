/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

/**
 * Base interface for all message types in the Gemini SDK. Provides common behavior for
 * different message types.
 */
public interface Message {

	/**
	 * Gets the type of this message.
	 */
	MessageType getType();

	/**
	 * Gets the content of this message.
	 */
	String getContent();

	/**
	 * Checks if this message is empty.
	 */
	default boolean isEmpty() {
		return getContent() == null || getContent().trim().isEmpty();
	}

}