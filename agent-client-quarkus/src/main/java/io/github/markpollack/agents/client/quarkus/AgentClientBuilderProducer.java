/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces an {@link AgentClient.Builder} for the application's {@link AgentApi}: a
 * lifecycle provider enabled through {@code agent-client.lifecycle.*}, or any
 * {@code AgentApi} bean the application defines. Each injection point gets a new builder,
 * as in the Spring Boot integration.
 */
@Singleton
public class AgentClientBuilderProducer {

	/**
	 * Creates a builder for the application's agent.
	 * @param agents the application's agents; exactly one is required
	 * @return a new builder for each injection point
	 */
	@Produces
	@Dependent
	public AgentClient.Builder agentClientBuilder(Instance<AgentApi> agents) {
		if (!agents.isResolvable()) {
			throw new IllegalStateException(agents.isUnsatisfied()
					? "No AgentApi bean: enable a lifecycle provider (agent-client.lifecycle.<provider>.enabled=true) or define one"
					: "More than one AgentApi bean: inject the one to use and call AgentClient.builder(agent)");
		}
		return AgentClient.builder(agents.get());
	}

}
