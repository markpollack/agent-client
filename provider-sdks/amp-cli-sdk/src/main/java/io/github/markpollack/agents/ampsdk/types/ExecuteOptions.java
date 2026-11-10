/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk.types;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for Amp CLI execute mode operations.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteOptions {

	private final boolean dangerouslyAllowAll;

	private final Duration timeout;

	private final String model;

	private final Map<String, Object> mcpServers;

	private ExecuteOptions(Builder builder) {
		this.dangerouslyAllowAll = builder.dangerouslyAllowAll;
		this.timeout = builder.timeout;
		this.model = builder.model;
		this.mcpServers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.mcpServers));
	}

	public boolean isDangerouslyAllowAll() {
		return dangerouslyAllowAll;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public String getModel() {
		return model;
	}

	/**
	 * MCP servers for this run, keyed by name, each in the shape of an
	 * {@code amp.mcpServers} settings entry: {@code command}, {@code args} and
	 * {@code env} for a local server, or {@code url} and {@code headers} for a remote
	 * one. They are passed with {@code --mcp-config} and merged with the user's settings.
	 * @return the servers, empty when none are configured
	 */
	public Map<String, Object> getMcpServers() {
		return mcpServers;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ExecuteOptions defaultOptions() {
		return builder().build();
	}

	public static final class Builder {

		private boolean dangerouslyAllowAll = true;

		private Duration timeout = Duration.ofMinutes(5);

		private String model;

		private Map<String, Object> mcpServers = Map.of();

		private Builder() {
		}

		public Builder dangerouslyAllowAll(boolean dangerouslyAllowAll) {
			this.dangerouslyAllowAll = dangerouslyAllowAll;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder mcpServers(Map<String, Object> mcpServers) {
			this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();
			return this;
		}

		public ExecuteOptions build() {
			return new ExecuteOptions(this);
		}

	}

}
