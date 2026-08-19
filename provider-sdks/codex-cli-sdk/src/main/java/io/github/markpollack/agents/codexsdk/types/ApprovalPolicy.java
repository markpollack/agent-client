/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

/**
 * Approval policies for Codex CLI command and edit approvals.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public enum ApprovalPolicy {

	/**
	 * Never ask for approval - fully autonomous. Default for exec mode.
	 */
	NEVER("never"),

	/**
	 * Smart approval - ask only for potentially dangerous operations. Default for
	 * interactive mode.
	 */
	SMART("smart"),

	/**
	 * Always ask for approval before any operation.
	 */
	ALWAYS("always");

	private final String value;

	ApprovalPolicy(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

}
