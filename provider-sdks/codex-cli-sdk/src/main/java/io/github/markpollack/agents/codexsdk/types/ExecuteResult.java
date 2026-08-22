/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import java.time.Duration;
import java.util.List;
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

	private final List<String> rolloutLines;

	public ExecuteResult(String output, String activityLog, int exitCode, Duration duration, String model) {
		this(output, activityLog, exitCode, duration, model, List.of());
	}

	public ExecuteResult(String output, String activityLog, int exitCode, Duration duration, String model,
			List<String> rolloutLines) {
		this.output = output;
		this.activityLog = activityLog;
		this.exitCode = exitCode;
		this.duration = duration;
		this.model = model;
		this.sessionId = extractSessionId(activityLog);
		this.rolloutLines = rolloutLines != null ? List.copyOf(rolloutLines) : List.of();
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

	/**
	 * Gets the raw persisted Codex rollout JSONL for this run. Codex writes the
	 * trajectory to its session store rather than to the ordinary {@code exec} output.
	 * The list is empty when no matching, flushed rollout could be found within the
	 * transport's bounded wait.
	 * @return immutable raw rollout lines, possibly empty
	 */
	public List<String> getRolloutLines() {
		return rolloutLines;
	}

	public boolean isSuccessful() {
		return exitCode == 0;
	}

}
