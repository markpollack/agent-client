/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.micronaut;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;

/**
 * Provides an {@link AgentClient.Builder} for the application's {@link AgentApi}: a
 * lifecycle provider created from {@code agent-client.lifecycle.*} properties, or any
 * {@code AgentApi} bean the application defines. Each injection point gets a new builder,
 * as in the Spring Boot integration.
 */
@Factory
public class AgentClientBuilderFactory {

	/**
	 * Creates a builder for the application's agent.
	 * @param agentApi the agent, normally a lifecycle provider
	 * @return a new builder for each injection point
	 */
	@Prototype
	@Requires(beans = AgentApi.class)
	public AgentClient.Builder agentClientBuilder(AgentApi agentApi) {
		return AgentClient.builder(agentApi);
	}

}
