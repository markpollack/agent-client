/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionRegistry;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/**
 * Claude Code CLI implementation of {@link AgentSessionRegistry}. Manages
 * {@link ClaudeAgentSession} instances backed by live CLI processes.
 *
 * <p>
 * Each {@link #create(Path)} call starts a Claude Code CLI process, reserves a session ID
 * without submitting a model prompt. The returned session is fully initialized and ready
 * for {@link AgentSession#prompt(String)} calls.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * ClaudeAgentSessionRegistry registry = ClaudeAgentSessionRegistry.builder()
 *     .timeout(Duration.ofMinutes(5))
 *     .build();
 *
 * AgentSession session = registry.create(Paths.get("/my/project"));
 * AgentResponse response = session.prompt("create a REST controller");
 * session.close();
 * }</pre>
 *
 * <p>
 * For Spring applications, wire as a bean and use {@code @Scheduled} to call
 * {@link #evictStale(Duration)} periodically. Note: requires {@code @EnableScheduling} on
 * the application configuration.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.10.0
 */
public class ClaudeAgentSessionRegistry implements AgentSessionRegistry {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeAgentSessionRegistry.class);

	private final Duration timeout;

	private final String claudePath;

	private final HookRegistry hookRegistry;

	private final ClaudeAgentOptions defaultOptions;

	private final ConcurrentHashMap<String, ClaudeAgentSession> sessions = new ConcurrentHashMap<>();

	protected ClaudeAgentSessionRegistry(Builder builder) {
		this.timeout = builder.timeout;
		this.claudePath = builder.claudePath;
		this.hookRegistry = builder.hookRegistry != null ? builder.hookRegistry : new HookRegistry();
		this.defaultOptions = builder.defaultOptions;
	}

	/**
	 * Creates a new builder for {@link ClaudeAgentSessionRegistry}.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentSession create(Path workingDirectory) {
		return open(workingDirectory, null, null);
	}

	@Override
	public AgentSession create(Path workingDirectory, String serverName,
			io.github.markpollack.agents.model.mcp.McpServerDefinition definition) {
		if (serverName == null || !serverName.matches("[a-zA-Z0-9_-]+")) {
			throw new IllegalArgumentException("MCP server name must contain letters, digits, underscores or hyphens");
		}
		java.util.Objects.requireNonNull(definition, "definition");
		return open(workingDirectory, serverName, definition);
	}

	private AgentSession open(Path directory, String serverName,
			io.github.markpollack.agents.model.mcp.McpServerDefinition definition) {
		java.util.Objects.requireNonNull(directory, "workingDirectory");
		String id = java.util.UUID.randomUUID().toString();
		ClaudeSessionConfiguration configuration = ClaudeSessionConfiguration.create(directory, id, defaultOptions,
				timeout, serverName, definition);
		ClaudeSyncClient client = null;
		try {
			client = newClient(configuration.initial(), directory);
			client.connect();
			ClaudeAgentSession session = new ClaudeAgentSession(id, directory, client, configuration,
					options -> newClient(options, directory), serverName,
					(definitionNow, environment) -> ClaudeSessionConfiguration.create(directory, id, defaultOptions,
							timeout, serverName, definitionNow, environment));
			sessions.put(id, session);
			return session;
		}
		catch (Exception ex) {
			if (client != null) {
				try {
					client.close();
				}
				catch (RuntimeException cleanup) {
					ex.addSuppressed(cleanup);
				}
			}
			try {
				configuration.close();
			}
			catch (RuntimeException cleanup) {
				ex.addSuppressed(cleanup);
			}
			throw new IllegalStateException("Failed to open Claude conversation with scoped MCP configuration", ex);
		}
	}

	// SDK boundary, replaceable by deterministic adapter tests without starting a CLI.
	ClaudeSyncClient newClient(CLIOptions options, Path directory) {
		return ClaudeClient.sync(options)
			.workingDirectory(directory)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build();
	}

	@Override
	public Optional<AgentSession> find(String sessionId) {
		return Optional.ofNullable(sessions.get(sessionId));
	}

	@Override
	public void evict(String sessionId) {
		ClaudeAgentSession session = sessions.remove(sessionId);
		if (session != null) {
			session.close();
			logger.debug("Evicted session {}", sessionId);
		}
	}

	@Override
	public void evictStale(Duration inactiveSince) {
		Instant threshold = Instant.now().minus(inactiveSince);
		sessions.forEach((id, session) -> {
			if (session.getLastActivity().isBefore(threshold)) {
				evict(id);
			}
		});
	}

	/**
	 * Returns the number of active sessions in this registry.
	 * @return the session count
	 */
	public int size() {
		return sessions.size();
	}

	/**
	 * Builder for {@link ClaudeAgentSessionRegistry}.
	 */
	public static class Builder {

		private Duration timeout = Duration.ofMinutes(10);

		private String claudePath;

		private HookRegistry hookRegistry;

		private ClaudeAgentOptions defaultOptions;

		private Builder() {
		}

		/**
		 * Sets the default timeout for session operations.
		 * @param timeout the timeout duration
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Sets the path to the Claude CLI executable.
		 * @param claudePath the path to Claude CLI
		 * @return this builder
		 */
		public Builder claudePath(String claudePath) {
			this.claudePath = claudePath;
			return this;
		}

		/**
		 * Sets the hook registry shared across all sessions.
		 * @param hookRegistry the hook registry
		 * @return this builder
		 */
		public Builder hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		/**
		 * Sets default agent options (model, timeout, etc.) for sessions.
		 * @param defaultOptions the default options
		 * @return this builder
		 */
		public Builder defaultOptions(ClaudeAgentOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Builds the registry.
		 * @return a new {@link ClaudeAgentSessionRegistry}
		 */
		public ClaudeAgentSessionRegistry build() {
			return new ClaudeAgentSessionRegistry(this);
		}

	}

}
