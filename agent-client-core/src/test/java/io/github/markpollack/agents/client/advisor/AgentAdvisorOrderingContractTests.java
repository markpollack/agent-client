/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.markpollack.agents.client.Ordered;
import io.github.markpollack.agents.client.advisor.api.AgentAdvisor;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link AgentAdvisor} takes its ordering from Client's own portable {@link Ordered},
 * with no Spring type in its hierarchy, and keeps the values the Spring-based contract
 * had.
 */
class AgentAdvisorOrderingContractTests {

	@Test
	void advisorHierarchyHasNoSpringType() {
		List<String> supertypes = new ArrayList<>();
		Deque<Class<?>> pending = new ArrayDeque<>(List.of(AgentCallAdvisor.class));
		while (!pending.isEmpty()) {
			Class<?> type = pending.pop();
			supertypes.add(type.getName());
			pending.addAll(List.of(type.getInterfaces()));
		}
		assertThat(supertypes).contains(AgentAdvisor.class.getName(), Ordered.class.getName())
			.noneMatch(name -> name.startsWith("org.springframework."));
	}

	@Test
	void clientOrderedDeclaresTheOrder() {
		assertThatNoException().isThrownBy(() -> Ordered.class.getDeclaredMethod("getOrder"));
		assertThat(Ordered.class.isAssignableFrom(AgentAdvisor.class)).isTrue();
	}

	@Test
	void precedenceConstantsKeepTheirValues() {
		assertThat(Ordered.HIGHEST_PRECEDENCE).isEqualTo(Integer.MIN_VALUE);
		assertThat(Ordered.LOWEST_PRECEDENCE).isEqualTo(Integer.MAX_VALUE);
		assertThat(AgentAdvisor.HIGHEST_PRECEDENCE).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
		assertThat(AgentAdvisor.LOWEST_PRECEDENCE).isEqualTo(Ordered.LOWEST_PRECEDENCE);
		assertThat(AgentAdvisor.DEFAULT_AGENT_PRECEDENCE_ORDER).isEqualTo(Integer.MIN_VALUE + 1000);
	}

}
