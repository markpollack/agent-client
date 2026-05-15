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
 * // session.getSessionId() returns real UUID — already established
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
	 * Returns the unique session identifier. Never null or "default" — the session is
	 * eagerly initialized at creation time.
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
