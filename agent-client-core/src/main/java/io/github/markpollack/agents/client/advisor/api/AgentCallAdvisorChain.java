/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor.api;

import java.util.List;

import io.github.markpollack.agents.client.AgentClientRequest;
import io.github.markpollack.agents.client.AgentClientResponse;

/**
 * A chain of {@link AgentCallAdvisor} instances orchestrating the execution of an
 * {@link AgentClientRequest} on the next {@link AgentCallAdvisor} in the chain.
 *
 * <p>
 * Follows the Spring AI advisor chain pattern for consistency with the Spring AI
 * ecosystem.
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public interface AgentCallAdvisorChain {

	/**
	 * Invokes the next {@link AgentCallAdvisor} in the {@link AgentCallAdvisorChain} with
	 * the given request.
	 * @param request the agent client request
	 * @return the agent client response from the next advisor or terminal model call
	 */
	AgentClientResponse nextCall(AgentClientRequest request);

	/**
	 * Returns the list of all {@link AgentCallAdvisor} instances included in this chain
	 * at the time of its creation.
	 * @return the list of call advisors
	 */
	List<AgentCallAdvisor> getCallAdvisors();

}
