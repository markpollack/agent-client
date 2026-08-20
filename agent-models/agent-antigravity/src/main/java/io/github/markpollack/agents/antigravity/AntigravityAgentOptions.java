/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.antigravitysdk.types.ExecutionMode;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Antigravity-specific {@link AgentOptions}.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class AntigravityAgentOptions implements AgentOptions {

	private final String model;

	private final String reasoningEffort;

	private final Duration timeout;

	private final String workingDirectory;

	private final Map<String, String> environmentVariables;

	private final boolean dangerouslySkipPermissions;

	private final ExecutionMode executionMode;

	private final List<Path> additionalDirectories;

	private final Integer maxTurns;

	private final String systemInstructions;

	private final Map<String, Object> jsonSchema;

	private final String executablePath;

	private AntigravityAgentOptions(Builder builder) {
		this.model = builder.model;
		this.reasoningEffort = builder.reasoningEffort;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.environmentVariables = (builder.environmentVariables != null) ? Map.copyOf(builder.environmentVariables)
				: Map.of();
		this.dangerouslySkipPermissions = builder.dangerouslySkipPermissions;
		this.executionMode = builder.executionMode;
		this.additionalDirectories = (builder.additionalDirectories != null)
				? List.copyOf(builder.additionalDirectories) : List.of();
		this.maxTurns = builder.maxTurns;
		this.systemInstructions = builder.systemInstructions;
		this.jsonSchema = (builder.jsonSchema != null) ? Map.copyOf(builder.jsonSchema) : Map.of();
		this.executablePath = builder.executablePath;
	}

	@Override
	public String getModel() {
		return this.model;
	}

	@Override
	public Duration getTimeout() {
		return this.timeout;
	}

	@Override
	public String getWorkingDirectory() {
		return this.workingDirectory;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return this.environmentVariables;
	}

	@Override
	public Integer getMaxTurns() {
		return this.maxTurns;
	}

	@Override
	public String getSystemInstructions() {
		return this.systemInstructions;
	}

	@Override
	public Map<String, Object> getJsonSchema() {
		return this.jsonSchema;
	}

	@Override
	public String getEffort() {
		return this.reasoningEffort;
	}

	/**
	 * Portable auto-approve maps to {@code --dangerously-skip-permissions}.
	 *
	 * <p>
	 * There is no gentler setting that works unattended. Antigravity's headless default
	 * is to soft-deny a tool call it cannot get approval for and carry on reporting
	 * success, so declining to auto-approve does not make the run safer — it makes the
	 * run quietly partial.
	 */
	@Override
	public boolean isAutoApprove() {
		return this.dangerouslySkipPermissions;
	}

	public String getReasoningEffort() {
		return this.reasoningEffort;
	}

	public boolean isDangerouslySkipPermissions() {
		return this.dangerouslySkipPermissions;
	}

	public ExecutionMode getExecutionMode() {
		return this.executionMode;
	}

	public List<Path> getAdditionalDirectories() {
		return this.additionalDirectories;
	}

	public String getExecutablePath() {
		return this.executablePath;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String model;

		private String reasoningEffort;

		private Duration timeout;

		private String workingDirectory;

		private Map<String, String> environmentVariables;

		private boolean dangerouslySkipPermissions;

		private ExecutionMode executionMode;

		private List<Path> additionalDirectories;

		private Integer maxTurns;

		private String systemInstructions;

		private Map<String, Object> jsonSchema;

		private String executablePath;

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder reasoningEffort(String reasoningEffort) {
			this.reasoningEffort = reasoningEffort;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder environmentVariables(Map<String, String> environmentVariables) {
			this.environmentVariables = environmentVariables;
			return this;
		}

		public Builder dangerouslySkipPermissions(boolean dangerouslySkipPermissions) {
			this.dangerouslySkipPermissions = dangerouslySkipPermissions;
			return this;
		}

		public Builder executionMode(ExecutionMode executionMode) {
			this.executionMode = executionMode;
			return this;
		}

		public Builder additionalDirectories(List<Path> additionalDirectories) {
			this.additionalDirectories = additionalDirectories;
			return this;
		}

		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder systemInstructions(String systemInstructions) {
			this.systemInstructions = systemInstructions;
			return this;
		}

		public Builder jsonSchema(Map<String, Object> jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		public Builder executablePath(String executablePath) {
			this.executablePath = executablePath;
			return this;
		}

		public AntigravityAgentOptions build() {
			return new AntigravityAgentOptions(this);
		}

	}

}
