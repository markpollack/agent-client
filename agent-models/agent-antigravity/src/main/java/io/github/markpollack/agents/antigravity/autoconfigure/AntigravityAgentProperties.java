/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity.autoconfigure;

import java.time.Duration;

import io.github.markpollack.agents.antigravitysdk.types.ExecutionMode;
import io.github.markpollack.agents.model.AgentClientMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Antigravity agent.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
@ConfigurationProperties(prefix = "agent-client.antigravity")
public class AntigravityAgentProperties {

	private AgentClientMode mode;

	private String model = "gemini-3.1-pro-high";

	private String effort;

	/**
	 * Default well above the CLI's own five-minute {@code --print-timeout}, which is
	 * short for anything but a toy prompt.
	 */
	private Duration timeout = Duration.ofMinutes(15);

	/**
	 * Nullable so an explicit choice stays distinguishable from a derived one. Null means
	 * "derive from {@link #mode}"; a set value always wins.
	 */
	private Boolean dangerouslySkipPermissions;

	private ExecutionMode executionMode;

	private boolean sandbox;

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

	public String getEffort() {
		return this.effort;
	}

	public void setEffort(String effort) {
		this.effort = effort;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	/**
	 * Whether to approve every tool call up front.
	 *
	 * <p>
	 * An explicit setting always wins. Otherwise STRICT declines, and LOOSE approves.
	 *
	 * <p>
	 * Note what declining actually buys here, because it is not what the flag name
	 * suggests. Antigravity's headless default is not to stop on an unapprovable tool
	 * call — it is to refuse that call, carry on, and report success. So STRICT does not
	 * make an unattended run safer; it makes it silently partial. It is the right default
	 * only because a caller that has not thought about permissions should not be handed
	 * unrestricted execution, and because {@code AntigravityAgentModel} reports the
	 * refusals rather than swallowing them.
	 * @return true to pass {@code --dangerously-skip-permissions}
	 */
	public boolean isDangerouslySkipPermissions() {
		if (this.dangerouslySkipPermissions != null) {
			return this.dangerouslySkipPermissions;
		}
		return this.mode != AgentClientMode.STRICT;
	}

	public void setDangerouslySkipPermissions(Boolean dangerouslySkipPermissions) {
		this.dangerouslySkipPermissions = dangerouslySkipPermissions;
	}

	public ExecutionMode getExecutionMode() {
		return this.executionMode;
	}

	public void setExecutionMode(ExecutionMode executionMode) {
		this.executionMode = executionMode;
	}

	public boolean isSandbox() {
		return this.sandbox;
	}

	public void setSandbox(boolean sandbox) {
		this.sandbox = sandbox;
	}

	public String getExecutablePath() {
		return this.executablePath;
	}

	public void setExecutablePath(String executablePath) {
		this.executablePath = executablePath;
	}

}
