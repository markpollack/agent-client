/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

import java.nio.file.Path;

/**
 * A persistent, multi-turn agent conversation. Analogous to {@code HttpSession} — the
 * session exists before you use it and maintains state across prompts.
 *
 * <p>
 * Sessions are created via {@link AgentSessionRegistry#create(Path)} and support
 * multi-turn continuation through {@link #prompt(String)}. If the underlying transport
 * dies, the session transitions to {@link AgentSessionStatus#DEAD} and can be resurrected
 * with {@link #resume()}.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * AgentSession session = registry.create(Paths.get("/my/project"));
 * // session.getSessionId() returns a UUID reserved for this conversation
 *
 * AgentResponse r1 = session.prompt("create a REST app");
 * AgentResponse r2 = session.prompt("add Spring Data JDBC");
 * session.close();
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.10.0
 * @see AgentSessionRegistry
 * @see AgentSessionStatus
 */
public interface AgentSession extends AutoCloseable {

	/**
	 * Returns the unique session identifier. Never null or "default". The provider may
	 * reserve the ID at creation without submitting a prompt.
	 * @return the session ID
	 */
	String getSessionId();

	/**
	 * Returns the working directory this session is anchored to. Immutable — a session
	 * cannot change its working directory after creation.
	 * @return the working directory path
	 */
	Path getWorkingDirectory();

	/**
	 * Returns the current session lifecycle status.
	 * @return the session status
	 */
	AgentSessionStatus getStatus();

	/**
	 * Sends a follow-up prompt in this session's conversation context. This is a
	 * continuation, not a new conversation — previous turns are preserved.
	 * @param message the prompt message
	 * @return the agent response
	 * @throws IllegalStateException if the session is {@link AgentSessionStatus#DEAD}
	 */
	AgentResponse prompt(String message);

	/**
	 * Prompts with synchronous, ordered observation of messages, tool activity and one
	 * terminal outcome. Observers must return promptly. Overlapping turns are rejected.
	 * An observer exception fails the turn and releases its provider resources.
	 * @param message the prompt
	 * @param observer the event observer
	 * @return the completed response
	 * @throws UnsupportedOperationException if observation is unsupported
	 */
	default AgentResponse prompt(String message, java.util.function.Consumer<AgentSessionEvent> observer) {
		throw new UnsupportedOperationException("Session observation is not supported");
	}

	/**
	 * Cancels only this session's active turn and releases its provider resources.
	 * Idempotent when idle or already cancelled. A DEAD session requires resume before
	 * the next prompt. Application-owned tool handlers are cancelled by the application.
	 * @throws UnsupportedOperationException if cancellation is unsupported
	 */
	default void cancelActiveTurn() {
		throw new UnsupportedOperationException("Session cancellation is not supported");
	}

	/**
	 * Resurrects a dead session by spawning a fresh transport process with the same
	 * session ID. The conversation history is restored from the session transcript.
	 * @return this session, now in {@link AgentSessionStatus#RESUMED} state
	 * @throws IllegalStateException if the session is not {@link AgentSessionStatus#DEAD}
	 */
	AgentSession resume();

	/**
	 * Branches the conversation from the current point, creating a new independent
	 * session with the same history up to this point.
	 * @return a new forked session
	 * @throws UnsupportedOperationException if the provider does not support forking
	 */
	AgentSession fork();

	@Override
	void close();

}
