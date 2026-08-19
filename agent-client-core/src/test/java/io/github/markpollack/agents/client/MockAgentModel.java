/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * Mock agent model for testing AgentClient functionality.
 */
class MockAgentModel implements AgentModel {

	AgentTaskRequest lastRequest;

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		this.lastRequest = request;

		// Create mock response
		String responseText = "Mock response for: " + request.goal();
		AgentGenerationMetadata generationMetadata = new AgentGenerationMetadata("SUCCESS", Map.of());
		AgentGeneration generation = new AgentGeneration(responseText, generationMetadata);

		AgentResponseMetadata responseMetadata = new AgentResponseMetadata("mock-model", Duration.ofMillis(100),
				"mock-session", Map.of());

		return new AgentResponse(List.of(generation), responseMetadata);
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

}