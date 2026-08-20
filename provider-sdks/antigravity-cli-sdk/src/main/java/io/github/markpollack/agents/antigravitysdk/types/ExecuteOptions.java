/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.types;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for one Antigravity CLI execution.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class ExecuteOptions {

	private final String model;

	private final String effort;

	private final Duration timeout;

	private final Path workingDirectory;

	private final List<Path> additionalDirectories;

	private final boolean dangerouslySkipPermissions;

	private final boolean sandbox;

	private final ExecutionMode mode;

	private final String jsonSchema;

	private final String agent;

	private final String executablePath;

	private ExecuteOptions(Builder builder) {
		this.model = builder.model;
		this.effort = builder.effort;
		this.timeout = (builder.timeout != null) ? builder.timeout : Duration.ofMinutes(10);
		this.workingDirectory = builder.workingDirectory;
		this.additionalDirectories = (builder.additionalDirectories != null)
				? List.copyOf(builder.additionalDirectories) : List.of();
		this.dangerouslySkipPermissions = builder.dangerouslySkipPermissions;
		this.sandbox = builder.sandbox;
		this.mode = builder.mode;
		this.jsonSchema = builder.jsonSchema;
		this.agent = builder.agent;
		this.executablePath = builder.executablePath;
	}

	public String getModel() {
		return this.model;
	}

	public String getEffort() {
		return this.effort;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public Path getWorkingDirectory() {
		return this.workingDirectory;
	}

	public List<Path> getAdditionalDirectories() {
		return this.additionalDirectories;
	}

	public boolean isDangerouslySkipPermissions() {
		return this.dangerouslySkipPermissions;
	}

	public boolean isSandbox() {
		return this.sandbox;
	}

	public ExecutionMode getMode() {
		return this.mode;
	}

	public String getJsonSchema() {
		return this.jsonSchema;
	}

	public String getAgent() {
		return this.agent;
	}

	public String getExecutablePath() {
		return this.executablePath;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ExecuteOptions defaults() {
		return builder().build();
	}

	public static class Builder {

		private String model;

		private String effort;

		private Duration timeout;

		private Path workingDirectory;

		private List<Path> additionalDirectories;

		private boolean dangerouslySkipPermissions;

		private boolean sandbox;

		private ExecutionMode mode;

		private String jsonSchema;

		private String agent;

		private String executablePath;

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder effort(String effort) {
			this.effort = effort;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder additionalDirectories(List<Path> additionalDirectories) {
			this.additionalDirectories = additionalDirectories;
			return this;
		}

		public Builder dangerouslySkipPermissions(boolean dangerouslySkipPermissions) {
			this.dangerouslySkipPermissions = dangerouslySkipPermissions;
			return this;
		}

		public Builder sandbox(boolean sandbox) {
			this.sandbox = sandbox;
			return this;
		}

		public Builder mode(ExecutionMode mode) {
			this.mode = mode;
			return this;
		}

		public Builder jsonSchema(String jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		public Builder agent(String agent) {
			this.agent = agent;
			return this;
		}

		public Builder executablePath(String executablePath) {
			this.executablePath = executablePath;
			return this;
		}

		public ExecuteOptions build() {
			return new ExecuteOptions(this);
		}

	}

}
