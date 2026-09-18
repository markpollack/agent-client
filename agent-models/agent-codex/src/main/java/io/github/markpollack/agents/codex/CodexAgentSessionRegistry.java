/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionRegistry;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Codex exec conversations with exact-thread continuation and fixed HTTP MCP
 * configuration. Opening validates the CLI without a model prompt. The application
 * chooses this registry. Provider acceptance and authentication are checked only when the
 * first real turn runs.
 */
public class CodexAgentSessionRegistry implements AgentSessionRegistry {

	private final Builder settings;

	private final ConcurrentHashMap<String, CodexAgentSession> sessions = new ConcurrentHashMap<>();

	protected CodexAgentSessionRegistry(Builder builder) {
		settings = new Builder().model(builder.model)
			.timeout(builder.timeout)
			.codexPath(builder.codexPath)
			.approvedTools(builder.approvedTools)
			.environmentVariables(builder.environmentVariables);
	}

	/**
	 * @return a registry builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentSession create(Path directory) {
		return open(directory, null, null);
	}

	@Override
	public AgentSession create(Path directory, String name, McpServerDefinition definition) {
		Objects.requireNonNull(definition, "definition");
		return open(directory, name, definition);
	}

	private AgentSession open(Path directory, String name, McpServerDefinition definition) {
		Objects.requireNonNull(directory, "workingDirectory");
		var options = CodexSessionConfiguration.create(directory, settings.model, settings.timeout, name, definition,
				settings.approvedTools, settings.environmentVariables);
		var session = new CodexAgentSession(directory, newClient(options, directory));
		sessions.put(session.getSessionId(), session);
		return session;
	}

	// SDK boundary for deterministic tests; no installed provider is required.
	CodexClient newClient(ExecuteOptions options, Path directory) {
		return CodexClient.create(options, directory, settings.codexPath);
	}

	@Override
	public Optional<AgentSession> find(String id) {
		return Optional.ofNullable(sessions.get(id));
	}

	@Override
	public void evict(String id) {
		var session = sessions.remove(id);
		if (session != null) {
			session.close();
		}
	}

	@Override
	public void evictStale(Duration inactivity) {
		Instant threshold = Instant.now().minus(inactivity);
		sessions.forEach((id, session) -> {
			if (session.getLastActivity().isBefore(threshold)) {
				evict(id);
			}
		});
	}

	/** Provider settings, fixed when the registry is built. */
	public static class Builder {

		private String model;

		private String codexPath;

		private Duration timeout = Duration.ofMinutes(10);

		private Set<String> approvedTools = Set.of();

		private Map<String, String> environmentVariables = Map.of();

		private Builder() {
		}

		/**
		 * @param model model name, or null for the CLI default
		 * @return this builder
		 */
		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * @param path CLI executable path, or null for discovery
		 * @return this builder
		 */
		public Builder codexPath(String path) {
			codexPath = path;
			return this;
		}

		/**
		 * @param timeout positive per-turn deadline
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			if (timeout == null || timeout.isNegative() || timeout.isZero()) {
				throw new IllegalArgumentException("Timeout must be positive");
			}
			this.timeout = timeout;
			return this;
		}

		/**
		 * Sets tool names approved only on the supplied scoped server. Names describe
		 * provider policy, not Java callbacks. Required for scoped MCP creation under
		 * never approval and read-only sandbox; unrelated configured tools are untouched.
		 * @param names exact MCP tool names
		 * @return this builder
		 */
		public Builder approvedTools(Set<String> names) {
			approvedTools = Set.copyOf(names);
			return this;
		}

		/**
		 * Sets fixed conversation environment overrides for every child invocation.
		 * Values override inherited variables and are never logged by the adapter.
		 * @param environmentVariables variable names and values, copied on assignment
		 * @return this builder
		 */
		public Builder environmentVariables(Map<String, String> environmentVariables) {
			this.environmentVariables = Map.copyOf(environmentVariables);
			return this;
		}

		/**
		 * @return a registry
		 */
		public CodexAgentSessionRegistry build() {
			return new CodexAgentSessionRegistry(this);
		}

	}

}
