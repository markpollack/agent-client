/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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