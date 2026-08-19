/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Result of a Codex CLI execution.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteResult {

	private static final Pattern SESSION_ID_PATTERN = Pattern.compile("session id:\\s*([a-f0-9\\-]+)");

	private final String output;

	private final String activityLog;

	private final int exitCode;

	private final Duration duration;

	private final String model;

	private final String sessionId;

	public ExecuteResult(String output, String activityLog, int exitCode, Duration duration, String model) {
		this.output = output;
		this.activityLog = activityLog;
		this.exitCode = exitCode;
		this.duration = duration;
		this.model = model;
		this.sessionId = extractSessionId(activityLog);
	}

	/**
	 * Extracts session ID from Codex output. Session ID appears in stderr output like:
	 * "session id: 0199b2f0-e92a-76b3-88fa-a0fa925ad545"
	 * @param activityLog the stderr activity log
	 * @return extracted session ID or null if not found
	 */
	private String extractSessionId(String activityLog) {
		if (activityLog == null || activityLog.isEmpty()) {
			return null;
		}

		Matcher matcher = SESSION_ID_PATTERN.matcher(activityLog);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	public String getOutput() {
		return output;
	}

	public String getActivityLog() {
		return activityLog;
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

	public String getSessionId() {
		return sessionId;
	}

	public boolean isSuccessful() {
		return exitCode == 0;
	}

}
