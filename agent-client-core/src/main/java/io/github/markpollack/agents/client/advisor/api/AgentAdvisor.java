/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor.api;

import org.springframework.core.Ordered;

/**
 * Parent advisor interface for all agent advisors. Follows the Spring AI advisor pattern
 * for consistent integration with the Spring AI ecosystem.
 *
 * <p>
 * Advisors allow intercepting and augmenting agent execution flow, enabling use cases
 * like:
 * <ul>
 * <li>Context injection
 * <li>Post-execution evaluation (judges, validators)
 * <li>Metrics collection and logging
 * <li>Request/response transformation
 * </ul>
 *
 * @author Mark Pollack
 * @since 0.1.0
 * @see AgentCallAdvisor
 */
public interface AgentAdvisor extends Ordered {

	/**
	 * Default precedence order for agent advisors. Ensures this order has lower priority
	 * than Spring AI internal advisors, leaving room (1000 slots) for custom advisors
	 * with higher priority.
	 */
	int DEFAULT_AGENT_PRECEDENCE_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

	/**
	 * Return the name of the advisor for identification and logging.
	 * @return the advisor name
	 */
	String getName();

}
