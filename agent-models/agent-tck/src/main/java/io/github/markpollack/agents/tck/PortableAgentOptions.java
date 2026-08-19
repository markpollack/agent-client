/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

import java.time.Duration;
import java.util.Map;

import io.github.markpollack.agents.model.AgentOptions;

/**
 * Portable {@link AgentOptions} implementation for integration testing. Carries the
 * cross-cutting options ({@code maxTurns}, {@code autoApprove},
 * {@code systemInstructions}) that every provider should honor, without importing any
 * provider-specific classes.
 *
 * <p>
 * Use this in provider test modules to verify portable option fallback paths. The TCK
 * module is available as a test-scoped dependency in all provider modules.
 * </p>
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
public final class PortableAgentOptions implements AgentOptions {

	private final Integer maxTurns;

	private final boolean autoApprove;

	private final String systemInstructions;

	private final Map<String, Object> jsonSchema;

	private final String effort;

	public PortableAgentOptions(Integer maxTurns, boolean autoApprove, String systemInstructions) {
		this(maxTurns, autoApprove, systemInstructions, null, null);
	}

	public PortableAgentOptions(Integer maxTurns, boolean autoApprove, String systemInstructions,
			Map<String, Object> jsonSchema) {
		this(maxTurns, autoApprove, systemInstructions, jsonSchema, null);
	}

	public PortableAgentOptions(Integer maxTurns, boolean autoApprove, String systemInstructions,
			Map<String, Object> jsonSchema, String effort) {
		this.maxTurns = maxTurns;
		this.autoApprove = autoApprove;
		this.systemInstructions = systemInstructions;
		this.jsonSchema = jsonSchema;
		this.effort = effort;
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
		return this.systemInstructions;
	}

	@Override
	public Map<String, Object> getJsonSchema() {
		return this.jsonSchema;
	}

	@Override
	public String getWorkingDirectory() {
		return null;
	}

	@Override
	public Duration getTimeout() {
		return null;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return Map.of();
	}

	@Override
	public String getModel() {
		return null;
	}

	@Override
	public String getEffort() {
		return this.effort;
	}

}
