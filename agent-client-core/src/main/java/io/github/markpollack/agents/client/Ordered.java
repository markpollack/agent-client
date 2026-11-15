/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

/**
 * Agent Client's own ordering contract, free of any framework. A lower
 * {@link #getOrder()} runs earlier, and objects with the same order keep the order in
 * which they were added.
 *
 * <p>
 * Client sorts by this interface alone. A framework integration (Spring Boot, Quarkus,
 * Micronaut) adapts to it, for example by mapping {@link #getOrder()} to its own ordering
 * when it collects beans. Client does not adapt to a framework. The constants keep the
 * values of Spring's {@code Ordered}, so existing order values mean the same thing.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public interface Ordered {

	/**
	 * The highest precedence: an object with this order runs first.
	 */
	int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

	/**
	 * The lowest precedence: an object with this order runs last.
	 */
	int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

	/**
	 * Return this object's order. Lower values run earlier.
	 * @return the order value
	 * @see #HIGHEST_PRECEDENCE
	 * @see #LOWEST_PRECEDENCE
	 */
	int getOrder();

}
