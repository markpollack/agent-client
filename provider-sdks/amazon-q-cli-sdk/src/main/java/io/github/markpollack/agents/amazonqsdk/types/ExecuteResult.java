/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonqsdk.types;

import java.time.Duration;

/**
 * Result of Amazon Q CLI execution.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteResult {

	private final String output;

	private final int exitCode;

	private final String model;

	private final Duration duration;

	private final String conversationId;

	public ExecuteResult(String output, int exitCode, String model, Duration duration, String conversationId) {
		this.output = output;
		this.exitCode = exitCode;
		this.model = model;
		this.duration = duration;
		this.conversationId = conversationId;
	}

	public String getOutput() {
		return output;
	}

	public int getExitCode() {
		return exitCode;
	}

	public String getModel() {
		return model;
	}

	public Duration getDuration() {
		return duration;
	}

	public String getConversationId() {
		return conversationId;
	}

	public boolean isSuccessful() {
		return exitCode == 0;
	}

	@Override
	public String toString() {
		return "ExecuteResult{" + "output='" + output + '\'' + ", exitCode=" + exitCode + ", model='" + model + '\''
				+ ", duration=" + duration + ", conversationId='" + conversationId + '\'' + '}';
	}

}
