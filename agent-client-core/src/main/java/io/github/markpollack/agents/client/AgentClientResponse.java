/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.HashMap;
import java.util.Map;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;

/**
 * Client-layer response type for agent execution flows with advisor support. Provides a
 * context map for advisors to share data and evaluation results.
 *
 * <p>
 * Follows the Spring AI ChatClientResponse pattern for consistency with the Spring AI
 * ecosystem.
 *
 * @param agentResponse the underlying agent model response
 * @param context mutable context map for advisors (evaluation results, metrics, etc.)
 * @author Mark Pollack
 * @since 0.1.0
 */
public record AgentClientResponse(AgentResponse agentResponse, Map<String, Object> context) {

	/**
	 * Convenience constructor with empty context map.
	 * @param agentResponse the underlying agent model response
	 */
	public AgentClientResponse(AgentResponse agentResponse) {
		this(agentResponse, new HashMap<>());
	}

	/**
	 * Primary outcome string (backward compatibility method).
	 * @return the primary result text
	 */
	public String getResult() {
		return this.agentResponse.getResult() != null ? this.agentResponse.getResult().getOutput() : "";
	}

	/**
	 * Access structured model-layer response (backward compatibility method).
	 * @return the underlying agent response
	 */
	public AgentResponse getAgentResponse() {
		return this.agentResponse;
	}

	/**
	 * Get the response metadata (backward compatibility method).
	 * @return the response metadata
	 */
	public AgentResponseMetadata getMetadata() {
		return this.agentResponse.getMetadata();
	}

	/**
	 * Check if the agent task was successful (backward compatibility method).
	 * @return true if successful
	 */
	public boolean isSuccessful() {
		return this.agentResponse.getResult() != null
				&& "SUCCESS".equals(this.agentResponse.getResult().getMetadata().getFinishReason());
	}

	/**
	 * Get the parsed provider trajectory from Agent Journal capture if present.
	 * <p>
	 * Retrieves the provider-specific phase capture stored after parsing the CLI's
	 * richest available run surface. Capture is not gated on raw trace-file
	 * configuration.
	 * </p>
	 * @param <T> the phase capture type
	 * @return the phase capture, or null when the provider does not publish one
	 */
	@SuppressWarnings("unchecked")
	public <T> T getPhaseCapture() {
		return (T) this.agentResponse.getMetadata().get("phaseCapture");
	}

}
