/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With no provider enabled the application starts, and asking for a builder says why it
 * cannot have one.
 */
class NoProviderTest {

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar -> jar.addClass(Fixture.class))
		.overrideConfigKey("agent-client.lifecycle.claude.executable", "claude");

	@Inject
	Instance<AgentApi> agents;

	@Inject
	Instance<AgentClient.Builder> builders;

	@Test
	void nothingIsCreatedUnlessAProviderIsEnabled() {
		assertThat(this.agents.isUnsatisfied()).isTrue();
		assertThatThrownBy(() -> this.builders.get()).hasStackTraceContaining("No AgentApi bean");
	}

}
