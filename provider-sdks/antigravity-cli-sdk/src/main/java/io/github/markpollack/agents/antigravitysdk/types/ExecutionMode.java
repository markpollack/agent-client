/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.types;

/**
 * Antigravity's {@code --mode} values.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public enum ExecutionMode {

	ACCEPT_EDITS("accept-edits"), PLAN("plan");

	private final String value;

	ExecutionMode(String value) {
		this.value = value;
	}

	public String getValue() {
		return this.value;
	}

}
