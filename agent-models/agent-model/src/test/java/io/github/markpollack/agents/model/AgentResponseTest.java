/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AgentResponse.
 *
 * @author Mark Pollack
 */
class AgentResponseTest {

	@Test
	void createSuccessResult() {
		AgentGenerationMetadata generationMetadata = new AgentGenerationMetadata("SUCCESS", java.util.Map.of());
		AgentGeneration generation = new AgentGeneration("Test output", generationMetadata);

		AgentResponseMetadata responseMetadata = new AgentResponseMetadata("test-model", Duration.ofMinutes(2),
				"session-123", java.util.Map.of());

		AgentResponse result = new AgentResponse(List.of(generation), responseMetadata);

		assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
		assertThat(result.getResult().getOutput()).isEqualTo("Test output");
		assertThat(result.getMetadata().getDuration()).isEqualTo(Duration.ofMinutes(2));
		assertThat(result.getMetadata().getModel()).isEqualTo("test-model");
		assertThat(result.getMetadata().getSessionId()).isEqualTo("session-123");
	}

	@Test
	void statusEnumValues() {
		assertThat("SUCCESS").isNotNull();
		assertThat("ERROR").isNotNull();
	}

}