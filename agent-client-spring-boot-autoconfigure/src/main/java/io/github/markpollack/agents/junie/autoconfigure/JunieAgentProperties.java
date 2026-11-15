/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie.autoconfigure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Junie agent.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
@ConfigurationProperties(prefix = "agent-client.junie")
public class JunieAgentProperties {

	/**
	 * The {@code junie} executable. Resolved from {@code PATH} when unset.
	 */
	private String executablePath;

	private String model;

	private String effort;

	/**
	 * Junie plans and edits across multiple turns, so the default is generous. The ACP
	 * request timeout is derived from this.
	 */
	private Duration timeout = Duration.ofMinutes(15);

	/**
	 * Where Junie keeps session directories. Defaults to {@code ~/.junie/sessions}, which
	 * is where the native {@code events.jsonl} trajectory is read from.
	 */
	private Path sessionsDirectory;

	/**
	 * Trajectory capture is on by default. Set false to opt out; a consumer who wants a
	 * journal should not have to wire one, and a consumer who does not should be able to
	 * say so here.
	 */
	private boolean captureEnabled = true;

	/**
	 * Passthrough Junie CLI flags, keyed by long flag name without leading dashes. A
	 * {@code true} boolean becomes a bare flag. This is the escape hatch that keeps new
	 * Junie flags from requiring a release of this library.
	 */
	private Map<String, Object> extras = new LinkedHashMap<>();

	public String getExecutablePath() {
		return this.executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getEffort() {
		return this.effort;
	}

	public void setEffort(String effort) {
		this.effort = effort;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Path getSessionsDirectory() {
		return this.sessionsDirectory;
	}

	public void setSessionsDirectory(Path sessionsDirectory) {
		this.sessionsDirectory = sessionsDirectory;
	}

	public boolean isCaptureEnabled() {
		return this.captureEnabled;
	}

	public void setCaptureEnabled(boolean captureEnabled) {
		this.captureEnabled = captureEnabled;
	}

	public Map<String, Object> getExtras() {
		return this.extras;
	}

	public void setExtras(Map<String, Object> extras) {
		this.extras = (extras != null) ? extras : new LinkedHashMap<>();
	}

}
