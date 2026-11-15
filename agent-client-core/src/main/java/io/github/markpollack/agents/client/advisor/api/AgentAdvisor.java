/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor.api;

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
 * Advisors run in ascending {@link #getOrder()} order: a lower value runs earlier, and
 * advisors with the same value keep the order in which they were added. The ordering
 * contract is Agent Client's own and needs no Spring type. Its constants keep the values
 * of Spring's {@code Ordered}, so existing order values mean the same thing.
 *
 * @author Mark Pollack
 * @since 0.1.0
 * @see AgentCallAdvisor
 */
public interface AgentAdvisor {

	/**
	 * The highest precedence: an advisor with this order runs first.
	 */
	int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

	/**
	 * The lowest precedence: an advisor with this order runs last.
	 */
	int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

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

	/**
	 * Return this advisor's order. Lower values run earlier.
	 * @return the order value
	 * @see #HIGHEST_PRECEDENCE
	 * @see #LOWEST_PRECEDENCE
	 */
	int getOrder();

}
