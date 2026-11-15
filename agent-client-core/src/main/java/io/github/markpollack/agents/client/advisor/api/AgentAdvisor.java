/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor.api;

import io.github.markpollack.agents.client.Ordered;

/**
 * Parent interface for all agent advisors.
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
 * <p>
 * Advisors run in ascending {@link #getOrder()} order, as defined by Client's own
 * {@link Ordered}: a lower value runs earlier, and advisors with the same value keep the
 * order in which they were added.
 *
 * @author Mark Pollack
 * @since 0.1.0
 * @see AgentCallAdvisor
 */
public interface AgentAdvisor extends Ordered {

	/**
	 * Default precedence order for agent advisors, leaving room (1000 slots) for custom
	 * advisors with higher priority.
	 */
	int DEFAULT_AGENT_PRECEDENCE_ORDER = HIGHEST_PRECEDENCE + 1000;

	/**
	 * Return the name of the advisor for identification and logging.
	 * @return the advisor name
	 */
	String getName();

}
