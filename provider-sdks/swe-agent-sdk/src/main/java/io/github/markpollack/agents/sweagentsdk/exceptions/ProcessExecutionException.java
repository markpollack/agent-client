/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagentsdk.exceptions;

/**
 * Exception thrown when a process execution fails.
 *
 * <p>
 * This exception captures detailed information about process failures, including exit
 * codes, stderr output, and command details.
 * </p>
 */
public class ProcessExecutionException extends SweSDKException {

	private final int exitCode;

	private final String command;

	private final String stderr;

	/**
	 * Creates a new ProcessExecutionException.
	 * @param message the detail message
	 * @param exitCode the process exit code
	 * @param command the command that was executed
	 * @param stderr the stderr output from the process
	 */
	public ProcessExecutionException(String message, int exitCode, String command, String stderr) {
		super(formatMessage(message, exitCode, command, stderr));
		this.exitCode = exitCode;
		this.command = command;
		this.stderr = stderr;
	}

	/**
	 * Creates a new ProcessExecutionException with a cause.
	 * @param message the detail message
	 * @param exitCode the process exit code
	 * @param command the command that was executed
	 * @param stderr the stderr output from the process
	 * @param cause the underlying cause
	 */
	public ProcessExecutionException(String message, int exitCode, String command, String stderr, Throwable cause) {
		super(formatMessage(message, exitCode, command, stderr), cause);
		this.exitCode = exitCode;
		this.command = command;
		this.stderr = stderr;
	}

	/**
	 * Gets the process exit code.
	 * @return the exit code
	 */
	public int getExitCode() {
		return exitCode;
	}

	/**
	 * Gets the command that was executed.
	 * @return the command string
	 */
	public String getCommand() {
		return command;
	}

	/**
	 * Gets the stderr output from the process.
	 * @return the stderr output
	 */
	public String getStderr() {
		return stderr;
	}

	/**
	 * Formats the exception message with process details.
	 */
	private static String formatMessage(String message, int exitCode, String command, String stderr) {
		StringBuilder sb = new StringBuilder();
		sb.append(message);
		sb.append("\nCommand: ").append(command);
		sb.append("\nExit Code: ").append(exitCode);
		if (stderr != null && !stderr.trim().isEmpty()) {
			sb.append("\nStderr: ").append(stderr.trim());
		}
		return sb.toString();
	}

}