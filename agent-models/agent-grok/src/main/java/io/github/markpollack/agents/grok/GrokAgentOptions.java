/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Grok-specific {@link AgentOptions}.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class GrokAgentOptions implements AgentOptions {

	private final String model;

	private final String reasoningEffort;

	private final Duration timeout;

	private final String workingDirectory;

	private final Map<String, String> environmentVariables;

	private final PermissionMode permissionMode;

	private final Integer maxTurns;

	private final String systemInstructions;

	private final Map<String, Object> jsonSchema;

	private final List<String> allowedTools;

	private final List<String> disallowedTools;

	private final boolean disableWebSearch;

	private final String executablePath;

	private GrokAgentOptions(Builder builder) {
		this.model = builder.model;
		this.reasoningEffort = builder.reasoningEffort;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.environmentVariables = (builder.environmentVariables != null) ? Map.copyOf(builder.environmentVariables)
				: Map.of();
		this.permissionMode = builder.permissionMode;
		this.maxTurns = builder.maxTurns;
		this.systemInstructions = builder.systemInstructions;
		this.jsonSchema = (builder.jsonSchema != null) ? Map.copyOf(builder.jsonSchema) : Map.of();
		this.allowedTools = (builder.allowedTools != null) ? List.copyOf(builder.allowedTools) : List.of();
		this.disallowedTools = (builder.disallowedTools != null) ? List.copyOf(builder.disallowedTools) : List.of();
		this.disableWebSearch = builder.disableWebSearch;
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
	 * Portable auto-approve maps to {@code bypassPermissions}, which is the only Grok
	 * permission mode that grants every tool call without a prompt.
	 */
	@Override
	public boolean isAutoApprove() {
		return this.permissionMode == PermissionMode.BYPASS_PERMISSIONS;
	}

	public String getReasoningEffort() {
		return this.reasoningEffort;
	}

	public PermissionMode getPermissionMode() {
		return this.permissionMode;
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

	public static class Builder {

		private String model;

		private String reasoningEffort;

		private Duration timeout;

		private String workingDirectory;

		private Map<String, String> environmentVariables;

		private PermissionMode permissionMode;

		private Integer maxTurns;

		private String systemInstructions;

		private Map<String, Object> jsonSchema;

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

		public Builder workingDirectory(String workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder environmentVariables(Map<String, String> environmentVariables) {
			this.environmentVariables = environmentVariables;
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

		public Builder systemInstructions(String systemInstructions) {
			this.systemInstructions = systemInstructions;
			return this;
		}

		public Builder jsonSchema(Map<String, Object> jsonSchema) {
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

		public GrokAgentOptions build() {
			return new GrokAgentOptions(this);
		}

	}

}
