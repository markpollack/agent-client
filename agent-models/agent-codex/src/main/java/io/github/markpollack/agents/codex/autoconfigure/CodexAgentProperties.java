/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex.autoconfigure;

import io.github.markpollack.agents.model.AgentClientMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Codex Agent Model.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "agent-client.codex")
public class CodexAgentProperties {

	/**
	 * Agent client mode controlling default permissiveness. When not set, inherits from
	 * {@code spring.ai.agents.mode} (default: LOOSE). Provider-specific property
	 * overrides (e.g., {@code skip-git-check}) take precedence over mode-derived
	 * defaults.
	 */
	private AgentClientMode mode;

	/**
	 * Model to use for Codex execution.
	 */
	private String model = "gpt-5.4-mini";

	/**
	 * Model reasoning effort ({@code model_reasoning_effort} config override).
	 * Codex-native values: minimal, low, medium, high, xhigh. Null uses the CLI/config
	 * default.
	 */
	private String reasoningEffort;

	/**
	 * Timeout for agent task execution.
	 */
	private Duration timeout = Duration.ofMinutes(5);

	/**
	 * Enable full-auto mode (workspace-write sandbox + never approval).
	 */
	private boolean fullAuto = true;

	/**
	 * Skip git repository check. When not explicitly set, derived from mode: LOOSE
	 * defaults to true (works in any directory), STRICT defaults to false (requires git
	 * repository).
	 */
	private Boolean skipGitCheck;

	/**
	 * Bypass all sandbox restrictions and approval prompts. When not explicitly set,
	 * derived from mode: LOOSE defaults to true (no sandbox restrictions), STRICT
	 * defaults to false (uses workspace-write sandbox).
	 */
	private Boolean dangerouslyBypassSandbox;

	/**
	 * Path to the Codex CLI executable. If null, auto-discovery is used.
	 */
	private String executablePath;

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getReasoningEffort() {
		return reasoningEffort;
	}

	public void setReasoningEffort(String reasoningEffort) {
		this.reasoningEffort = reasoningEffort;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public boolean isFullAuto() {
		return fullAuto;
	}

	public void setFullAuto(boolean fullAuto) {
		this.fullAuto = fullAuto;
	}

	public AgentClientMode getMode() {
		return mode;
	}

	public void setMode(AgentClientMode mode) {
		this.mode = mode;
	}

	/**
	 * Returns whether to skip the git repository check. If explicitly set via
	 * {@code spring.ai.agents.codex.skip-git-check}, that value wins. Otherwise, derived
	 * from mode: LOOSE -> true, STRICT -> false, unset -> true (LOOSE is the default
	 * mode).
	 */
	public boolean isSkipGitCheck() {
		if (this.skipGitCheck != null) {
			return this.skipGitCheck;
		}
		if (this.mode == AgentClientMode.STRICT) {
			return false;
		}
		// Default: LOOSE behavior — skip git check for frictionless operation
		return true;
	}

	public void setSkipGitCheck(Boolean skipGitCheck) {
		this.skipGitCheck = skipGitCheck;
	}

	/**
	 * Returns whether to bypass sandbox restrictions. If explicitly set via
	 * {@code spring.ai.agents.codex.dangerously-bypass-sandbox}, that value wins.
	 * Otherwise, derived from mode: LOOSE -> true (no sandbox friction), STRICT -> false
	 * (uses workspace-write sandbox), unset -> true (LOOSE is the default mode).
	 */
	public boolean isDangerouslyBypassSandbox() {
		if (this.dangerouslyBypassSandbox != null) {
			return this.dangerouslyBypassSandbox;
		}
		if (this.mode == AgentClientMode.STRICT) {
			return false;
		}
		// Default: LOOSE behavior — bypass sandbox for frictionless operation
		return true;
	}

	public void setDangerouslyBypassSandbox(Boolean dangerouslyBypassSandbox) {
		this.dangerouslyBypassSandbox = dangerouslyBypassSandbox;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

}
