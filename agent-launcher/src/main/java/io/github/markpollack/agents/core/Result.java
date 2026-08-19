/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.core;

import java.util.Map;

/**
 * Result of agent execution.
 *
 * @param success true if agent executed successfully
 * @param message result message or error description
 * @param data additional data payload for judge integration
 * @author Mark Pollack
 * @since 1.1.0
 */
public record Result(boolean success, String message, Map<String, Object> data) {

	/**
	 * Create a successful result with message.
	 * @param message success message
	 * @return successful result
	 */
	public static Result ok(String message) {
		return new Result(true, message, Map.of());
	}

	/**
	 * Create a successful result with message and data.
	 * @param message success message
	 * @param data additional data payload
	 * @return successful result
	 */
	public static Result ok(String message, Map<String, Object> data) {
		return new Result(true, message, data);
	}

	/**
	 * Create a failed result with error message.
	 * @param message error message
	 * @return failed result
	 */
	public static Result fail(String message) {
		return new Result(false, message, Map.of());
	}

	/**
	 * Create a failed result with error message and data.
	 * @param message error message
	 * @param data additional data payload
	 * @return failed result
	 */
	public static Result fail(String message, Map<String, Object> data) {
		return new Result(false, message, data);
	}

}