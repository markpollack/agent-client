/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.Map;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Immutable accepted portable configuration; opaque extra data is refused before
 * admission.
 */
record ExecutionOptions(String workingDirectory, Duration timeout, Map<String, String> environment, String model,
		Map<String, Object> extras, Map<String, McpServerDefinition> mcp, Map<String, Object> schema, Integer maxTurns,
		boolean autoApprove, String instructions, String effort) implements AgentOptions {
	static ExecutionOptions copy(AgentOptions options) {
		return new ExecutionOptions(options.getWorkingDirectory(), options.getTimeout(),
				Map.copyOf(options.getEnvironmentVariables()), options.getModel(),
				ResponseCopies.map(options.getExtras()), Map.copyOf(options.getMcpServerDefinitions()),
				options.getJsonSchema() == null ? null : ResponseCopies.map(options.getJsonSchema()),
				options.getMaxTurns(), options.isAutoApprove(), options.getSystemInstructions(), options.getEffort());
	}

	@Override
	public String getWorkingDirectory() {
		return this.workingDirectory;
	}

	@Override
	public Duration getTimeout() {
		return this.timeout;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return this.environment;
	}

	@Override
	public String getModel() {
		return this.model;
	}

	@Override
	public Map<String, Object> getExtras() {
		return this.extras;
	}

	@Override
	public Map<String, McpServerDefinition> getMcpServerDefinitions() {
		return this.mcp;
	}

	@Override
	public Map<String, Object> getJsonSchema() {
		return this.schema;
	}

	@Override
	public Integer getMaxTurns() {
		return this.maxTurns;
	}

	@Override
	public boolean isAutoApprove() {
		return this.autoApprove;
	}

	@Override
	public String getSystemInstructions() {
		return this.instructions;
	}

	@Override
	public String getEffort() {
		return this.effort;
	}
}
