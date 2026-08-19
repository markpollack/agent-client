/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagentsdk.exceptions;

/**
 * Exception thrown when the mini-swe-agent CLI cannot be found or is not functional.
 *
 * <p>
 * This exception provides detailed information about CLI availability issues and includes
 * helpful installation instructions.
 * </p>
 *
 * <p>
 * <strong>Common causes:</strong>
 * </p>
 * <ul>
 * <li>mini-swe-agent CLI is not installed</li>
 * <li>CLI is not in PATH or specified location</li>
 * <li>CLI is installed but not executable</li>
 * <li>CLI version is incompatible</li>
 * </ul>
 */
public class SweCliNotFoundException extends SweSDKException {

	/**
	 * Creates a new SweCliNotFoundException with installation instructions.
	 * @param message the detail message describing the issue
	 */
	public SweCliNotFoundException(String message) {
		super(enhanceMessage(message));
	}

	/**
	 * Creates a new SweCliNotFoundException with cause.
	 * @param message the detail message describing the issue
	 * @param cause the underlying cause
	 */
	public SweCliNotFoundException(String message, Throwable cause) {
		super(enhanceMessage(message), cause);
	}

	/**
	 * Enhances the error message with installation instructions.
	 */
	private static String enhanceMessage(String message) {
		return message + "\n\n" + "To install mini-swe-agent CLI:\n"
				+ "1. Install via pip: pip install mini-swe-agent\n"
				+ "2. Or install via pipx: pipx install mini-swe-agent\n" + "3. Verify installation: mini --version\n"
				+ "4. Set OPENAI_API_KEY environment variable\n\n"
				+ "For more information, visit: https://github.com/SWE-agent/mini-swe-agent";
	}

}