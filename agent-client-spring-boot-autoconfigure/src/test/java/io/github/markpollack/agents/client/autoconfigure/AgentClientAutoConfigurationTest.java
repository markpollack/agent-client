/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.util.List;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentClientAutoConfigurationTest {

	private final AgentApi agent = request -> new AgentResponse(List.of(new AgentGeneration(request.goal())));

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AgentClientAutoConfiguration.class));

	@Test
	void requiresAnApplicationProvidedAgent() {
		this.contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentClient.Builder.class));
	}

	@Test
	void providesIndependentBuildersThatInvokeTheConfiguredAgent() {
		this.contextRunner.withBean(AgentApi.class, () -> this.agent).run(context -> {
			AgentClient.Builder first = context.getBean(AgentClient.Builder.class);
			AgentClient.Builder second = context.getBean(AgentClient.Builder.class);
			assertThat(first).isNotSameAs(second);
			assertThat(first.build().goal("first task").run().getResult()).isEqualTo("first task");
			assertThat(second.build().goal("second task").run().getResult()).isEqualTo("second task");
		});
	}

	@Test
	void backsOffForAnApplicationProvidedBuilder() {
		AgentClient.Builder supplied = AgentClient.builder(this.agent);
		this.contextRunner.withBean(AgentApi.class, () -> this.agent)
			.withBean(AgentClient.Builder.class, () -> supplied)
			.run(context -> {
				assertThat(context).hasSingleBean(AgentClient.Builder.class);
				assertThat(context.getBean(AgentClient.Builder.class)).isSameAs(supplied);
			});
	}

}
