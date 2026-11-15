/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.markpollack.agents.client.AgentClientRequest;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisorChain;

/**
 * Default implementation of {@link AgentCallAdvisorChain} using a Deque-based
 * chain-of-responsibility pattern.
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public class DefaultAgentCallAdvisorChain implements AgentCallAdvisorChain {

	private final List<AgentCallAdvisor> originalCallAdvisors;

	private final Deque<AgentCallAdvisor> callAdvisors;

	DefaultAgentCallAdvisorChain(Deque<AgentCallAdvisor> callAdvisors) {
		requireNonNull(callAdvisors, "the callAdvisors must be non-null");
		this.callAdvisors = callAdvisors;
		this.originalCallAdvisors = List.copyOf(callAdvisors);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentClientResponse nextCall(AgentClientRequest request) {
		requireNonNull(request, "the request cannot be null");

		if (this.callAdvisors.isEmpty()) {
			throw new IllegalStateException("No AgentCallAdvisors available to execute");
		}

		var advisor = this.callAdvisors.pop();
		return advisor.adviseCall(request, this);
	}

	@Override
	public List<AgentCallAdvisor> getCallAdvisors() {
		return this.originalCallAdvisors;
	}

	public static class Builder {

		private final Deque<AgentCallAdvisor> callAdvisors;

		public Builder() {
			this.callAdvisors = new ConcurrentLinkedDeque<>();
		}

		public Builder push(AgentCallAdvisor advisor) {
			requireNonNull(advisor, "the advisor must be non-null");
			return this.pushAll(List.of(advisor));
		}

		public Builder pushAll(List<AgentCallAdvisor> advisors) {
			requireNonNull(advisors, "the advisors must be non-null");
			if (advisors.stream().anyMatch(Objects::isNull)) {
				throw new IllegalArgumentException("the advisors must not contain null elements");
			}
			if (!advisors.isEmpty()) {
				advisors.forEach(this.callAdvisors::push);
				this.reOrder();
			}
			return this;
		}

		/**
		 * (Re)orders the advisors by ascending {@link AgentCallAdvisor#getOrder()}. The
		 * sort is stable, so advisors with equal order keep their relative position.
		 */
		private void reOrder() {
			ArrayList<AgentCallAdvisor> callAdvisors = new ArrayList<>(this.callAdvisors);
			callAdvisors.sort(Comparator.comparingInt(AgentCallAdvisor::getOrder));
			this.callAdvisors.clear();
			callAdvisors.forEach(this.callAdvisors::addLast);
		}

		public DefaultAgentCallAdvisorChain build() {
			return new DefaultAgentCallAdvisorChain(this.callAdvisors);
		}

	}

	/**
	 * Throws {@link IllegalArgumentException}, as the chain always has, when the value is
	 * null.
	 */
	private static void requireNonNull(Object value, String message) {
		if (value == null) {
			throw new IllegalArgumentException(message);
		}
	}

}
