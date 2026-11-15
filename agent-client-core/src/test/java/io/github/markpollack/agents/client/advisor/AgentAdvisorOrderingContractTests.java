/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.markpollack.agents.client.advisor.api.AgentAdvisor;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link AgentAdvisor} declares its own ordering contract, with no Spring type in its
 * hierarchy, and keeps the values and order the Spring-based contract had.
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
		assertThat(supertypes).contains(AgentAdvisor.class.getName())
			.noneMatch(name -> name.startsWith("org.springframework."));
	}

	@Test
	void advisorDeclaresItsOwnOrder() {
		assertThatNoException().isThrownBy(() -> AgentAdvisor.class.getDeclaredMethod("getOrder"));
	}

	@Test
	void precedenceConstantsKeepTheirValues() {
		assertThat(AgentAdvisor.HIGHEST_PRECEDENCE).isEqualTo(Integer.MIN_VALUE);
		assertThat(AgentAdvisor.LOWEST_PRECEDENCE).isEqualTo(Integer.MAX_VALUE);
		assertThat(AgentAdvisor.DEFAULT_AGENT_PRECEDENCE_ORDER).isEqualTo(Integer.MIN_VALUE + 1000);
	}

}
