/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

/**
 * Status enum for query results. Indicates the outcome of a query execution.
 */
public enum ResultStatus {

	/**
	 * Query completed successfully with results.
	 */
	SUCCESS,

	/**
	 * Query failed due to an error.
	 */
	ERROR,

	/**
	 * Query completed partially (some results may be incomplete).
	 */
	PARTIAL,

	/**
	 * Query timed out before completion.
	 */
	TIMEOUT,

	/**
	 * Query was cancelled before completion.
	 */
	CANCELLED

}