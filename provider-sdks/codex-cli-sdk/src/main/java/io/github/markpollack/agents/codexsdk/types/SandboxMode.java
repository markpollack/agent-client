/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

/**
 * Sandbox security modes for Codex CLI execution.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public enum SandboxMode {

	/**
	 * Read-only mode - no file edits or network commands allowed. Default for exec mode.
	 */
	READ_ONLY("read-only"),

	/**
	 * Workspace write mode - can edit files in working directory, /tmp, and $TMPDIR. No
	 * network access. Recommended for most autonomous operations.
	 */
	WORKSPACE_WRITE("workspace-write"),

	/**
	 * Danger mode - full file system and network access. Use with extreme caution.
	 */
	DANGER_FULL_ACCESS("danger-full-access");

	private final String value;

	SandboxMode(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

}
