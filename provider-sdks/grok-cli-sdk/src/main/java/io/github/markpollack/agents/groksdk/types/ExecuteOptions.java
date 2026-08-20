/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.types;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for one Grok CLI execution.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class ExecuteOptions {

	private final String model;

	private final String reasoningEffort;

	private final Duration timeout;

	private final Path workingDirectory;

	private final PermissionMode permissionMode;

	private final Integer maxTurns;

	private final String systemPromptOverride;

	private final String jsonSchema;

	private final List<String> allowedTools;

	private final List<String> disallowedTools;

	private final boolean disableWebSearch;

	private final String executablePath;

	private ExecuteOptions(Builder builder) {
		this.model = builder.model;
		this.reasoningEffort = builder.reasoningEffort;
		this.timeout = (builder.timeout != null) ? builder.timeout : Duration.ofMinutes(10);
		this.workingDirectory = builder.workingDirectory;
		this.permissionMode = builder.permissionMode;
		this.maxTurns = builder.maxTurns;
		this.systemPromptOverride = builder.systemPromptOverride;
		this.jsonSchema = builder.jsonSchema;
		this.allowedTools = (builder.allowedTools != null) ? List.copyOf(builder.allowedTools) : List.of();
		this.disallowedTools = (builder.disallowedTools != null) ? List.copyOf(builder.disallowedTools) : List.of();
		this.disableWebSearch = builder.disableWebSearch;
		this.executablePath = builder.executablePath;
	}

	public String getModel() {
		return this.model;
	}

	public String getReasoningEffort() {
		return this.reasoningEffort;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public Path getWorkingDirectory() {
		return this.workingDirectory;
	}

	public PermissionMode getPermissionMode() {
		return this.permissionMode;
	}

	public Integer getMaxTurns() {
		return this.maxTurns;
	}

	public String getSystemPromptOverride() {
		return this.systemPromptOverride;
	}

	public String getJsonSchema() {
		return this.jsonSchema;
	}

	public List<String> getAllowedTools() {
		return this.allowedTools;
	}

	public List<String> getDisallowedTools() {
		return this.disallowedTools;
	}

	public boolean isDisableWebSearch() {
		return this.disableWebSearch;
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

		private String reasoningEffort;

		private Duration timeout;

		private Path workingDirectory;

		private PermissionMode permissionMode;

		private Integer maxTurns;

		private String systemPromptOverride;

		private String jsonSchema;

		private List<String> allowedTools;

		private List<String> disallowedTools;

		private boolean disableWebSearch;

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

		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder systemPromptOverride(String systemPromptOverride) {
			this.systemPromptOverride = systemPromptOverride;
			return this;
		}

		public Builder jsonSchema(String jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = allowedTools;
			return this;
		}

		public Builder disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = disallowedTools;
			return this;
		}

		public Builder disableWebSearch(boolean disableWebSearch) {
			this.disableWebSearch = disableWebSearch;
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
