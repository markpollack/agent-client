/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Qwen Code Agent Model.
 *
 * @author Spring AI Community
 * @since 0.12.0
 */
@ConfigurationProperties(prefix = "agent-client.qwen-code")
public class QwenCodeAgentProperties {

	/**
	 * Model to use for Qwen Code execution.
	 */
	private String model = "qwen3-coder";

	/**
	 * Timeout for agent task execution.
	 */
	private Duration timeout = Duration.ofMinutes(5);

	/**
	 * Enable YOLO mode (all tools execute without confirmation).
	 */
	private boolean yolo = true;

	/**
	 * Path to the Qwen Code CLI executable. If null, auto-discovery is used.
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

	public boolean isYolo() {
		return yolo;
	}

	public void setYolo(boolean yolo) {
		this.yolo = yolo;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

}
