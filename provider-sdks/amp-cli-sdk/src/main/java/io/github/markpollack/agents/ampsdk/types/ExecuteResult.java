/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk.types;

import java.time.Duration;

/**
 * Result from Amp CLI execute mode operation.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteResult {

	private final String output;

	private final int exitCode;

	private final Duration duration;

	private final String model;

	public ExecuteResult(String output, int exitCode, Duration duration, String model) {
		this.output = output;
		this.exitCode = exitCode;
		this.duration = duration;
		this.model = model;
	}

	public String getOutput() {
		return output;
	}

	public int getExitCode() {
		return exitCode;
	}

	public Duration getDuration() {
		return duration;
	}

	public String getModel() {
		return model;
	}

	public boolean isSuccessful() {
		return exitCode == 0;
	}

}
