/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import io.github.markpollack.agents.antigravity.AntigravityClientProvider;
import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.codex.CodexClientProvider;
import io.github.markpollack.agents.model.AgentApi;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All three providers, each with every property set; with several agents the builder asks
 * for one.
 */
class AllProvidersTest {

	private static final String CAPTURE = Fixture.workingDirectory("capture").toString();

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar -> jar.addClass(Fixture.class))
		.overrideConfigKey("agent-client.lifecycle.claude.enabled", "true")
		.overrideConfigKey("agent-client.lifecycle.claude.acquisition-directory", CAPTURE)
		.overrideConfigKey("agent-client.lifecycle.claude.cli-version", "1.2.3")
		.overrideConfigKey("agent-client.lifecycle.codex.enabled", "true")
		.overrideConfigKey("agent-client.lifecycle.codex.executable", "codex")
		.overrideConfigKey("agent-client.lifecycle.codex.sandbox", "workspace-write")
		.overrideConfigKey("agent-client.lifecycle.antigravity.enabled", "true")
		.overrideConfigKey("agent-client.lifecycle.antigravity.sandbox", "true");

	@Inject
	ClaudeClientProvider claude;

	@Inject
	CodexClientProvider codex;

	@Inject
	AntigravityClientProvider antigravity;

	@Inject
	Instance<AgentApi> agents;

	@Inject
	Instance<AgentClient.Builder> builders;

	@Test
	void eachEnabledProviderIsABean() {
		assertThat(this.claude).isNotNull();
		assertThat(this.codex).isNotNull();
		assertThat(this.antigravity).isNotNull();
		assertThat(this.agents.stream()).hasSize(3);
	}

	@Test
	void severalAgentsNeedAnExplicitChoice() {
		assertThatThrownBy(() -> this.builders.get()).hasStackTraceContaining("More than one AgentApi bean");
	}

}
