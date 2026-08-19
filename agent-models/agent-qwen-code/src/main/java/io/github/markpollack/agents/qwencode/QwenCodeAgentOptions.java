/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode;

import java.time.Duration;
import java.util.Map;

import com.alibaba.qwen.code.cli.protocol.data.PermissionMode;

import io.github.markpollack.agents.model.AgentOptions;

/**
 * Configuration options for Qwen Code Agent Model implementations.
 *
 * @author Spring AI Community
 * @since 0.12.0
 */
public class QwenCodeAgentOptions implements AgentOptions {

	private String model = "qwen3-coder";

	private Duration timeout = Duration.ofMinutes(10);

	private boolean yolo = true;

	private PermissionMode permissionMode;

	private String executablePath;

	private String workingDirectory;

	private Map<String, String> environmentVariables = Map.of();

	private Map<String, Object> extras = Map.of();

	private QwenCodeAgentOptions() {
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public Duration getTimeout() {
		return timeout;
	}

	public boolean isYolo() {
		return yolo;
	}

	public PermissionMode getPermissionMode() {
		return permissionMode;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	@Override
	public String getWorkingDirectory() {
		return workingDirectory;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return environmentVariables;
	}

	@Override
	public Map<String, Object> getExtras() {
		return extras;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private final QwenCodeAgentOptions options = new QwenCodeAgentOptions();

		private Builder() {
		}

		public Builder model(String model) {
			options.model = model;
			return this;
		}

		public Builder timeout(Duration timeout) {
			options.timeout = timeout;
			return this;
		}

		public Builder yolo(boolean yolo) {
			options.yolo = yolo;
			if (yolo) {
				options.permissionMode = PermissionMode.YOLO;
			}
			return this;
		}

		public Builder permissionMode(PermissionMode permissionMode) {
			options.permissionMode = permissionMode;
			return this;
		}

		public Builder executablePath(String executablePath) {
			options.executablePath = executablePath;
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			options.workingDirectory = workingDirectory;
			return this;
		}

		public Builder environmentVariables(Map<String, String> environmentVariables) {
			options.environmentVariables = environmentVariables != null ? environmentVariables : Map.of();
			return this;
		}

		public Builder extras(Map<String, Object> extras) {
			options.extras = extras != null ? extras : Map.of();
			return this;
		}

		public QwenCodeAgentOptions build() {
			return options;
		}

	}

}
