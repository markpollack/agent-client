/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

/**
 * Setting sources for loading Claude Code configuration. Controls which filesystem
 * settings locations are loaded.
 *
 * @author Mark Pollack
 * @since 1.1.0
 */
public enum SettingSource {

	/**
	 * User-level settings from home directory (~/.config/claude or similar).
	 */
	USER("user"),

	/**
	 * Project-level settings from project root (.claude directory).
	 */
	PROJECT("project"),

	/**
	 * Local settings from current working directory.
	 */
	LOCAL("local");

	private final String value;

	SettingSource(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return value;
	}

}
