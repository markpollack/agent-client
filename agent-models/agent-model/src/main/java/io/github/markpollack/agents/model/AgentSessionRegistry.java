/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Factory and lifecycle manager for {@link AgentSession} instances. Analogous to
 * {@code SessionRepository} in Spring Session.
 *
 * <p>
 * The registry is pre-configured with provider-specific settings at construction time.
 * Each {@link #create(Path)} call opens a conversation ready for prompts. Opening is not
 * a paid authentication probe.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.10.0
 * @see AgentSession
 */
public interface AgentSessionRegistry {

	/**
	 * Opens a conversation without submitting a synthetic model prompt. Providers may
	 * reserve the session ID before the first prompt. Authentication and remote endpoint
	 * failures are reported when the provider reports them, including on the first turn.
	 * @param workingDirectory the directory the session operates in
	 * @return a conversation ready for prompts
	 * @throws IllegalStateException if the provider cannot open the conversation
	 */
	AgentSession create(Path workingDirectory);

	/**
	 * Opens a conversation with a fixed, named MCP connection. Implementations must
	 * preserve unrelated configured servers and reject unsupported configurations.
	 * Opening does not submit a synthetic model prompt. Remote connection failures may be
	 * reported on the first real prompt.
	 * @param workingDirectory the conversation directory
	 * @param serverName the scoped server name
	 * @param definition the immutable connection definition
	 * @return the conversation
	 * @throws UnsupportedOperationException if scoped MCP conversations are unsupported
	 */
	default AgentSession create(Path workingDirectory, String serverName,
			io.github.markpollack.agents.model.mcp.McpServerDefinition definition) {
		throw new UnsupportedOperationException("Scoped MCP conversations are not supported");
	}

	/**
	 * Finds an existing session by its ID.
	 * @param sessionId the session ID to look up
	 * @return the session, or empty if not found or evicted
	 */
	Optional<AgentSession> find(String sessionId);

	/**
	 * Removes a session from the registry and closes it.
	 * @param sessionId the session ID to evict
	 */
	void evict(String sessionId);

	/**
	 * Evicts all sessions that have been inactive longer than the given duration.
	 * Suitable for {@code @Scheduled} invocation in Spring applications.
	 * @param inactiveSince the inactivity threshold
	 */
	void evictStale(Duration inactiveSince);

}
