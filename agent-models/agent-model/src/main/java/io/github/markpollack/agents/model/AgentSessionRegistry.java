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
import java.time.Duration;
import java.util.Optional;

/**
 * Factory and lifecycle manager for {@link AgentSession} instances. Analogous to
 * {@code SessionRepository} in Spring Session.
 *
 * <p>
 * The registry is pre-configured with provider-specific settings at construction time.
 * Each {@link #create(Path)} call establishes a new session eagerly — the returned
 * session has a real session ID and is ready for {@link AgentSession#prompt(String)}
 * calls.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.10.0
 * @see AgentSession
 */
public interface AgentSessionRegistry {

	/**
	 * Creates a new session, eagerly establishing the CLI connection and capturing the
	 * real session ID from the first response. The returned session is fully initialized
	 * and ready for {@link AgentSession#prompt(String)} calls.
	 *
	 * <p>
	 * This method doubles as a startup probe — if it completes without exception, the CLI
	 * is installed, authenticated, and responsive. Callers can use a disposable session
	 * at startup to verify CLI health before accepting user requests:
	 * </p>
	 * <pre>{@code
	 * try {
	 *     AgentSession probe = registry.create(workingDirectory);
	 *     probe.close();
	 *     registry.evict(probe.getSessionId());
	 * } catch (IllegalStateException e) {
	 *     // CLI not available — surface error, refuse to start
	 * }
	 * }</pre>
	 * @param workingDirectory the directory the session operates in
	 * @return a fully-initialized session ready for prompts
	 * @throws IllegalStateException if the CLI is not found, exits abnormally, or fails
	 * to return a session ID
	 */
	AgentSession create(Path workingDirectory);

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
