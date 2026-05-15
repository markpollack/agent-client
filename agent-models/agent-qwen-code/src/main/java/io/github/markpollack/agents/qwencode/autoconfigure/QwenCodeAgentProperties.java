/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
