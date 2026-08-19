/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

/**
 * Enumerates the agent providers supported by the parity TCK.
 *
 * <p>
 * Used with {@link ProviderCapability} to declare which providers are expected to support
 * a given test scenario. Providers not listed for a scenario are skipped (reported as
 * NOT_APPLICABLE in surefire output) rather than failed.
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
public enum Provider {

	CLAUDE,

	CODEX,

	GEMINI,

	AMAZON_Q,

	AMP,

	QWEN_CODE,

	SWE_AGENT

}
