/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.exceptions;

/**
 * Exception thrown when Gemini CLI process execution fails. Contains detailed information
 * about the process failure.
 */
public class ProcessExecutionException extends GeminiSDKException {

	private final int exitCode;

	private final String stdout;

	private final String stderr;

	public ProcessExecutionException(String message, int exitCode, String stdout, String stderr) {
		super(String.format("%s (Exit Code: %d)", message, exitCode));
		this.exitCode = exitCode;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	public ProcessExecutionException(String message, int exitCode, String stdout, String stderr, Throwable cause) {
		super(String.format("%s (Exit Code: %d)", message, exitCode), cause);
		this.exitCode = exitCode;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	public int getExitCode() {
		return exitCode;
	}

	public String getStdout() {
		return stdout;
	}

	public String getStderr() {
		return stderr;
	}

}