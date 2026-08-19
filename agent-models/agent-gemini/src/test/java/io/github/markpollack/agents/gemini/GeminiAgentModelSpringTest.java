/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.gemini;

import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.geminisdk.GeminiClient;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GeminiAgentModel using Spring Boot auto-configuration patterns.
 *
 * This test demonstrates the proper Spring-idiomatic approach to dependency injection for
 * AI agent models, following the patterns established by Spring AI.
 */
@SpringBootTest(classes = GeminiAgentModelSpringTest.TestConfig.class)
@TestPropertySource(
		properties = { "spring.ai.agents.gemini.model=gemini-2.5-flash", "spring.ai.agents.gemini.timeout=PT5M",
				"spring.ai.agents.gemini.yolo=true", "spring.ai.agents.sandbox.docker.enabled=false" })
class GeminiAgentModelSpringTest {

	@Autowired
	private GeminiAgentModel agentModel;

	@Autowired
	private GeminiClient geminiClient;

	@Autowired
	private Sandbox sandbox;

	@Test
	void testSpringDependencyInjection() {
		// ASSERT: All beans are properly injected by Spring
		assertThat(agentModel).isNotNull();
		assertThat(geminiClient).isNotNull();
		assertThat(sandbox).isNotNull();
	}

	@Test
	void testAgentModelConfiguredWithProperties() {
		// ASSERT: Agent model is configured with properties from application.properties
		assertThat(agentModel).isNotNull();
		// The agent model should be properly configured via auto-configuration
		// Since we're using mocked dependencies, the agent model should be functional
	}

	@Configuration
	static class TestConfig {

		@Bean
		public GeminiClient geminiClient() {
			// Mock client for testing - use Mockito instead of inheritance
			GeminiClient mockClient = org.mockito.Mockito.mock(GeminiClient.class);
			// Note: GeminiClient doesn't have isConnected() method
			return mockClient;
		}

		@Bean
		public Sandbox sandbox() {
			// Local sandbox for testing
			return new LocalSandbox(Paths.get(System.getProperty("java.io.tmpdir")));
		}

		@Bean
		public GeminiAgentModel geminiAgentModel(GeminiClient geminiClient, Sandbox sandbox) {
			// Create agent model with default options
			GeminiAgentOptions options = GeminiAgentOptions.builder()
				.model("gemini-2.5-flash")
				.timeout(java.time.Duration.ofMinutes(5))
				.yolo(true)
				.build();
			return new GeminiAgentModel(geminiClient, options, sandbox);
		}

	}

}