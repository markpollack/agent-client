/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.markpollack.agents.client.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** Properties reach the Claude provider, and the builder runs a goal through it. */
class ClaudeProviderTest {

	private static final Path WORK = Fixture.workingDirectory("claude");

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar -> jar.addClass(Fixture.class))
		.overrideConfigKey("agent-client.lifecycle.claude.enabled", "true")
		.overrideConfigKey("agent-client.lifecycle.claude.executable", Fixture.cli().toString())
		.overrideConfigKey("agent-client.lifecycle.claude.working-directory", WORK.toString());

	@Inject
	AgentApi agent;

	@Inject
	Instance<AgentClient.Builder> builders;

	@Test
	void theBuilderRunsAGoalThroughTheConfiguredProvider() throws Exception {
		assertThat(this.agent).isInstanceOf(ClaudeClientProvider.class);
		try (AgentClient client = this.builders.get().build()) {
			assertThat(client.goal("cwd-marker:quarkus").run().isSuccessful()).isTrue();
		}
		assertThat(Files.readString(WORK.resolve("quarkus"))).isEqualTo(WORK.toString());
	}

	@Test
	void eachInjectionGetsItsOwnBuilder() {
		assertThat(this.builders.get()).isNotSameAs(this.builders.get());
	}

}
