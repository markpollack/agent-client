/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

/**
 * Controls the default permissiveness of AgentClient across all providers.
 *
 * <p>
 * {@code LOOSE} (default): Permissive defaults that work out of the box in any directory
 * with minimal preconditions. Designed for evaluation and development where friction
 * during onboarding is the primary failure mode.
 *
 * <p>
 * {@code STRICT}: Conservative defaults that require explicit opt-in to potentially risky
 * operations. Designed for production environments where safety is prioritized over
 * convenience.
 *
 * <p>
 * Each provider translates the mode into its own concrete defaults. For example, Codex
 * sets {@code skipGitCheck=true} in LOOSE mode and {@code skipGitCheck=false} in STRICT
 * mode.
 *
 * <p>
 * <strong>STRICT is a baseline, not a lock.</strong> Explicit provider-specific property
 * overrides always take precedence over mode-derived defaults. See the defaults
 * philosophy documentation for details.
 *
 * <p>
 * The SDK layer remains neutral on policy — mode translation is exclusively an
 * agent-models concern. Direct SDK consumers are never affected by AgentClientMode.
 *
 * @author Spring AI Community
 * @since 0.14.0
 * @see AgentOptions
 */
public enum AgentClientMode {

	/**
	 * Permissive defaults — works out of the box in any directory, minimal preconditions.
	 */
	LOOSE,

	/**
	 * Conservative defaults — requires explicit opt-in to potentially risky operations.
	 */
	STRICT

}
