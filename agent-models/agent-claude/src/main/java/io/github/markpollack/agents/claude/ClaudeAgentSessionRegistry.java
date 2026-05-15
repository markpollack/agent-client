/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionRegistry;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/**
 * Claude Code CLI implementation of {@link AgentSessionRegistry}. Manages
 * {@link ClaudeAgentSession} instances backed by live CLI processes.
 *
 * <p>
 * Each {@link #create(Path)} call starts a Claude Code CLI process, establishes a
 * session, and captures the real session ID from the CLI response. The returned session
 * is fully initialized and ready for {@link AgentSession#prompt(String)} calls.
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * <b>Error behavior:</b> Fails fast with {@link IllegalStateException} in three
	 * cases:
	 * </p>
	 * <ul>
	 * <li><b>CLI not found</b> — {@code connect()} throws {@code TransportException} when
	 * the Claude CLI binary is missing or {@code claudePath} is invalid. The SDK
	 * discovers the CLI via {@code ClaudeCliDiscovery}; if discovery fails, the error
	 * surfaces here.</li>
	 * <li><b>Process exits non-zero</b> — the CLI starts but crashes immediately (e.g.,
	 * auth failure, corrupt installation). {@code TransportException} includes the exit
	 * code.</li>
	 * <li><b>No session ID</b> — CLI responds but the {@code ResultMessage} has no
	 * session ID (null, empty, or "default"). This indicates a protocol mismatch or CLI
	 * version too old.</li>
	 * </ul>
	 *
	 * <p>
	 * In all failure cases the client is closed before the exception propagates — no
	 * leaked processes. Use {@link ClaudeAgentModel#isAvailable()} as a startup probe to
	 * catch CLI problems before any user calls {@code create()}.
	 * </p>
	 */
	@Override
	public AgentSession create(Path workingDirectory) {
		CLIOptions.Builder optionsBuilder = CLIOptions.builder();
		if (timeout != null) {
			optionsBuilder.timeout(timeout);
		}
		if (defaultOptions != null) {
			if (defaultOptions.getModel() != null) {
				optionsBuilder.model(defaultOptions.getModel());
			}
			if (defaultOptions.getMcpServers() != null && !defaultOptions.getMcpServers().isEmpty()) {
				optionsBuilder.mcpServers(defaultOptions.getMcpServers());
			}
		}
		CLIOptions options = optionsBuilder.build();

		ClaudeSyncClient client = ClaudeClient.sync(options)
			.workingDirectory(workingDirectory)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build();

		try {
			client.connect();
			Iterator<ParsedMessage> response = client.receiveResponse();
			PhaseCapture capture = SessionLogParser.parse(response, "session-init", "");
			String sessionId = capture.sessionId();

			if (sessionId == null || sessionId.isEmpty() || "default".equals(sessionId)) {
				client.close();
				throw new IllegalStateException("Failed to establish session — no session ID returned from CLI");
			}

			ClaudeAgentSession session = new ClaudeAgentSession(sessionId, workingDirectory, client, timeout,
					claudePath, hookRegistry);
			sessions.put(sessionId, session);

			logger.info("Created session {} in {}", sessionId, workingDirectory);
			return session;
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			client.close();
			throw new IllegalStateException("Failed to create session: " + ex.getMessage(), ex);
		}
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
