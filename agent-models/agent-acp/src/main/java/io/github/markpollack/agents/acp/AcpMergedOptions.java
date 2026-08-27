/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Per-call options overlaid on the model's defaults, field by field.
 *
 * <p>
 * Every adapter in this project hand-writes this overlay into a provider-specific
 * builder, which is only possible because each knows its own concrete options type.
 * {@link AcpAgentModel} does not — a caller may pass any {@link AgentOptions}
 * implementation — so the overlay is done against the interface instead. The rule is the
 * same one the hand-written versions apply: a value set on the request wins, and anything
 * left null falls through to the model's defaults.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
final class AcpMergedOptions implements AgentOptions {

	private final AgentOptions request;

	private final AgentOptions defaults;

	private AcpMergedOptions(AgentOptions request, AgentOptions defaults) {
		this.request = request;
		this.defaults = defaults;
	}

	/**
	 * Overlay request options on defaults, returning whichever one is sufficient when the
	 * other is absent.
	 * @param request the per-call options, possibly null
	 * @param defaults the model's defaults, possibly null
	 * @return merged options, or null when both are null
	 */
	static AgentOptions merge(AgentOptions request, AgentOptions defaults) {
		if (request == null) {
			return defaults;
		}
		if (defaults == null) {
			return request;
		}
		return new AcpMergedOptions(request, defaults);
	}

	@Override
	public String getWorkingDirectory() {
		return (this.request.getWorkingDirectory() != null) ? this.request.getWorkingDirectory()
				: this.defaults.getWorkingDirectory();
	}

	@Override
	public Duration getTimeout() {
		return (this.request.getTimeout() != null) ? this.request.getTimeout() : this.defaults.getTimeout();
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		Map<String, String> merged = new LinkedHashMap<>();
		if (this.defaults.getEnvironmentVariables() != null) {
			merged.putAll(this.defaults.getEnvironmentVariables());
		}
		if (this.request.getEnvironmentVariables() != null) {
			merged.putAll(this.request.getEnvironmentVariables());
		}
		return Map.copyOf(merged);
	}

	@Override
	public String getModel() {
		return (this.request.getModel() != null) ? this.request.getModel() : this.defaults.getModel();
	}

	@Override
	public Map<String, Object> getExtras() {
		Map<String, Object> merged = new LinkedHashMap<>(this.defaults.getExtras());
		merged.putAll(this.request.getExtras());
		return Map.copyOf(merged);
	}

	@Override
	public Map<String, McpServerDefinition> getMcpServerDefinitions() {
		return this.request.getMcpServerDefinitions().isEmpty() ? this.defaults.getMcpServerDefinitions()
				: this.request.getMcpServerDefinitions();
	}

	@Override
	public Map<String, Object> getJsonSchema() {
		return (this.request.getJsonSchema() != null && !this.request.getJsonSchema().isEmpty())
				? this.request.getJsonSchema() : this.defaults.getJsonSchema();
	}

	@Override
	public Integer getMaxTurns() {
		return (this.request.getMaxTurns() != null) ? this.request.getMaxTurns() : this.defaults.getMaxTurns();
	}

	@Override
	public boolean isAutoApprove() {
		return this.request.isAutoApprove() || this.defaults.isAutoApprove();
	}

	@Override
	public String getSystemInstructions() {
		return (this.request.getSystemInstructions() != null) ? this.request.getSystemInstructions()
				: this.defaults.getSystemInstructions();
	}

	@Override
	public String getEffort() {
		return (this.request.getEffort() != null) ? this.request.getEffort() : this.defaults.getEffort();
	}

}
