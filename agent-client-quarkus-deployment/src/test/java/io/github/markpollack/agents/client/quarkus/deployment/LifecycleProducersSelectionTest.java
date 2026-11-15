/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lifecycle producer is registered only for a provider that is enabled and on the
 * classpath.
 */
class LifecycleProducersSelectionTest {

	private static final String PRODUCERS = "io.github.markpollack.agents.client.quarkus.";

	@Test
	void anEnabledProviderWhoseModuleIsAbsentIsSkipped() {
		Set<String> enabled = Set.of("agent-client.lifecycle.claude.enabled", "agent-client.lifecycle.codex.enabled");
		Set<String> present = Set.of("io.github.markpollack.agents.client.codex.CodexClientProvider",
				"io.github.markpollack.agents.client.antigravity.AntigravityClientProvider");
		assertThat(AgentClientProcessor.lifecycleProducers(enabled::contains, present::contains))
			.containsExactly(PRODUCERS + "CodexLifecycleProducer");
	}

	@Test
	void everyEnabledAndPresentProviderIsRegistered() {
		assertThat(AgentClientProcessor.lifecycleProducers(key -> true, name -> true)).containsExactly(
				PRODUCERS + "AntigravityLifecycleProducer", PRODUCERS + "ClaudeLifecycleProducer",
				PRODUCERS + "CodexLifecycleProducer");
	}

}
