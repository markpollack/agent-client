/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
