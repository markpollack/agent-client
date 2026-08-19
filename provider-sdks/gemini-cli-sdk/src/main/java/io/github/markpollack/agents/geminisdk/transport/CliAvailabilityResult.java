/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.transport;

import java.util.Optional;

/**
 * Result of CLI availability check providing detailed information about CLI status.
 */
public record CliAvailabilityResult(boolean isAvailable, String version, String reason, Exception cause) {

	/**
	 * Creates a result indicating CLI is available.
	 */
	public static CliAvailabilityResult available(String version) {
		return new CliAvailabilityResult(true, version, null, null);
	}

	/**
	 * Creates a result indicating CLI is unavailable.
	 */
	public static CliAvailabilityResult unavailable(String reason, Exception cause) {
		return new CliAvailabilityResult(false, null, reason, cause);
	}

	/**
	 * Gets the CLI version if available.
	 */
	public Optional<String> getVersion() {
		return Optional.ofNullable(version);
	}

	/**
	 * Gets the reason for unavailability.
	 */
	public Optional<String> getReason() {
		return Optional.ofNullable(reason);
	}

	/**
	 * Gets the underlying cause exception if any.
	 */
	public Optional<Exception> getCause() {
		return Optional.ofNullable(cause);
	}

	/**
	 * Gets a human-readable status message.
	 */
	public String getStatusMessage() {
		if (isAvailable) {
			return String.format("Gemini CLI is available (version: %s)", version);
		}
		else {
			return String.format("Gemini CLI is unavailable: %s", reason);
		}
	}

	/**
	 * Throws an appropriate exception if CLI is unavailable.
	 */
	public void throwIfUnavailable() throws RuntimeException {
		if (!isAvailable) {
			if (cause != null) {
				throw new RuntimeException("Gemini CLI is unavailable: " + reason, cause);
			}
			else {
				throw new RuntimeException("Gemini CLI is unavailable: " + reason);
			}
		}
	}
}