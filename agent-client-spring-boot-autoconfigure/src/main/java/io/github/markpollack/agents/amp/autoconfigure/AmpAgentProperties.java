/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amp.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Amp Agent Model.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "agent-client.amp")
public class AmpAgentProperties {

	/**
	 * Model to use (if Amp CLI supports model selection).
	 */
	private String model = "amp-default";

	/**
	 * Timeout for agent task execution.
	 */
	private Duration timeout = Duration.ofMinutes(5);

	/**
	 * Whether to enable "dangerously allow all" mode (bypass all permission checks).
	 */
	private boolean dangerouslyAllowAll = true;

	/**
	 * Path to the Amp CLI executable. If null, auto-discovery is used.
	 */
	private String executablePath;

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public boolean isDangerouslyAllowAll() {
		return dangerouslyAllowAll;
	}

	public void setDangerouslyAllowAll(boolean dangerouslyAllowAll) {
		this.dangerouslyAllowAll = dangerouslyAllowAll;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

}
