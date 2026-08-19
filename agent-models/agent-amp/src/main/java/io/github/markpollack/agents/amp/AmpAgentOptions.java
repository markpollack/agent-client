/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amp;

import io.github.markpollack.agents.model.AgentOptions;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration options for Amp Agent Model implementations.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class AmpAgentOptions implements AgentOptions {

	/**
	 * The model name to use (if supported by Amp CLI).
	 */
	private String model;

	/**
	 * Timeout for agent execution.
	 */
	private Duration timeout = Duration.ofMinutes(10);

	/**
	 * Working directory for agent execution. If null, uses system temp directory.
	 */
	private String workingDirectory;

	/**
	 * Environment variables to set for the agent process.
	 */
	private Map<String, String> environmentVariables = Map.of();

	/**
	 * Extra provider-specific options for forward compatibility.
	 */
	private Map<String, Object> extras = Map.of();

	/**
	 * Path to the Amp CLI executable. If null, uses default discovery.
	 */
	private String executablePath;

	/**
	 * Dangerously allow all - bypasses ALL permission checks. When true, the agent will
	 * execute all operations without any safety prompts. USE WITH CAUTION.
	 */
	private boolean dangerouslyAllowAll = true;

	public AmpAgentOptions() {
	}

	public AmpAgentOptions(String model) {
		this.model = model;
	}

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

	public String getWorkingDirectory() {
		return workingDirectory;
	}

	public void setWorkingDirectory(String workingDirectory) {
		this.workingDirectory = workingDirectory;
	}

	public Map<String, String> getEnvironmentVariables() {
		return environmentVariables;
	}

	public void setEnvironmentVariables(Map<String, String> environmentVariables) {
		this.environmentVariables = environmentVariables != null ? environmentVariables : Map.of();
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

	public boolean isDangerouslyAllowAll() {
		return dangerouslyAllowAll;
	}

	public void setDangerouslyAllowAll(boolean dangerouslyAllowAll) {
		this.dangerouslyAllowAll = dangerouslyAllowAll;
	}

	@Override
	public Map<String, Object> getExtras() {
		return extras;
	}

	public void setExtras(Map<String, Object> extras) {
		this.extras = extras != null ? extras : Map.of();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for AmpAgentOptions.
	 */
	public static final class Builder {

		private final AmpAgentOptions options = new AmpAgentOptions();

		private Builder() {
		}

		public Builder model(String model) {
			options.setModel(model);
			return this;
		}

		public Builder timeout(Duration timeout) {
			options.setTimeout(timeout);
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			options.setWorkingDirectory(workingDirectory);
			return this;
		}

		public Builder environmentVariables(Map<String, String> environmentVariables) {
			options.setEnvironmentVariables(environmentVariables);
			return this;
		}

		public Builder executablePath(String executablePath) {
			options.setExecutablePath(executablePath);
			return this;
		}

		public Builder dangerouslyAllowAll(boolean dangerouslyAllowAll) {
			options.setDangerouslyAllowAll(dangerouslyAllowAll);
			return this;
		}

		public Builder extras(Map<String, Object> extras) {
			options.setExtras(extras);
			return this;
		}

		public AmpAgentOptions build() {
			return options;
		}

	}

}
