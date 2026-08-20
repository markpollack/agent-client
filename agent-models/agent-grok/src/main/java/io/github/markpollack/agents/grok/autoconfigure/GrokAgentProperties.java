/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok.autoconfigure;

import java.time.Duration;

import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.agents.model.AgentClientMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Grok agent.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
@ConfigurationProperties(prefix = "agent-client.grok")
public class GrokAgentProperties {

	private AgentClientMode mode;

	private String model = "grok-4.6";

	private String reasoningEffort;

	private Duration timeout = Duration.ofMinutes(5);

	/**
	 * Nullable so an explicit choice stays distinguishable from a derived one. Null means
	 * "derive from {@link #mode}"; a set value always wins.
	 */
	private PermissionMode permissionMode;

	private Integer maxTurns;

	private boolean disableWebSearch;

	private String executablePath;

	public AgentClientMode getMode() {
		return this.mode;
	}

	public void setMode(AgentClientMode mode) {
		this.mode = mode;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getReasoningEffort() {
		return this.reasoningEffort;
	}

	public void setReasoningEffort(String reasoningEffort) {
		this.reasoningEffort = reasoningEffort;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	/**
	 * Resolve the permission mode.
	 *
	 * <p>
	 * An explicit setting always wins. Otherwise STRICT keeps the CLI's own default,
	 * where a tool call that needs approval and cannot get one stops the run — which is
	 * what you want when a wrong action is worse than no action. LOOSE bypasses prompts,
	 * which is the only workable setting for an unattended run.
	 * @return the permission mode to pass to the CLI
	 */
	public PermissionMode getPermissionMode() {
		if (this.permissionMode != null) {
			return this.permissionMode;
		}
		return (this.mode == AgentClientMode.STRICT) ? PermissionMode.DEFAULT : PermissionMode.BYPASS_PERMISSIONS;
	}

	public void setPermissionMode(PermissionMode permissionMode) {
		this.permissionMode = permissionMode;
	}

	public Integer getMaxTurns() {
		return this.maxTurns;
	}

	public void setMaxTurns(Integer maxTurns) {
		this.maxTurns = maxTurns;
	}

	public boolean isDisableWebSearch() {
		return this.disableWebSearch;
	}

	public void setDisableWebSearch(boolean disableWebSearch) {
		this.disableWebSearch = disableWebSearch;
	}

	public String getExecutablePath() {
		return this.executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

}
