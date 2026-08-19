/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

/**
 * Status of an agent session's lifecycle.
 *
 * @author Mark Pollack
 * @since 0.10.0
 */
public enum AgentSessionStatus {

	/**
	 * Session is connected and ready for prompts.
	 */
	ACTIVE,

	/**
	 * Session transport has died. Call {@link AgentSession#resume()} to resurrect.
	 */
	DEAD,

	/**
	 * Session was resumed from a dead state and is now active again.
	 */
	RESUMED

}
