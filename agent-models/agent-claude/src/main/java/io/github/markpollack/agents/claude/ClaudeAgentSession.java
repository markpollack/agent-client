/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionStatus;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.streaming.MessageStreamIterator;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;

/**
 * Claude Code CLI implementation of {@link AgentSession}. Wraps a live
 * {@link ClaudeSyncClient} and supports multi-turn conversations via
 * {@link ClaudeSyncClient#query(String)}.
 *
 * <p>
 * Sessions are created by {@link ClaudeAgentSessionRegistry#create(Path)} and should not
 * be instantiated directly. The session holds the CLI process open between prompts — each
 * {@link #prompt(String)} call sends a follow-up in the same conversation context.
 * </p>
 *
 * <p>
 * If the CLI process dies mid-conversation, the session transitions to
 * {@link AgentSessionStatus#DEAD}. Call {@link #resume()} to spawn a fresh process with
 * {@code --resume} pointing to the same session ID.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.10.0
 */
public class ClaudeAgentSession implements AgentSession {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeAgentSession.class);

	private final String sessionId;

	private final Path workingDirectory;

	private final Duration timeout;

	private final String claudePath;

	private final HookRegistry hookRegistry;

	private ClaudeSyncClient client;

	private volatile AgentSessionStatus status;

	private Instant lastActivity;

	ClaudeAgentSession(String sessionId, Path workingDirectory, ClaudeSyncClient client, Duration timeout,
			String claudePath, HookRegistry hookRegistry) {
		this.sessionId = sessionId;
		this.workingDirectory = workingDirectory;
		this.client = client;
		this.timeout = timeout;
		this.claudePath = claudePath;
		this.hookRegistry = hookRegistry;
		this.status = AgentSessionStatus.ACTIVE;
		this.lastActivity = Instant.now();
	}

	@Override
	public String getSessionId() {
		return sessionId;
	}

	@Override
	public Path getWorkingDirectory() {
		return workingDirectory;
	}

	@Override
	public AgentSessionStatus getStatus() {
		return status;
	}

	/**
	 * Returns the last time this session was used (created, prompted, or resumed).
	 * @return the last activity timestamp
	 */
	public Instant getLastActivity() {
		return lastActivity;
	}

	@Override
	public AgentResponse prompt(String message) {
		if (status == AgentSessionStatus.DEAD) {
			throw new IllegalStateException("Session " + sessionId + " is dead. Call resume() to resurrect.");
		}

		Instant startTime = Instant.now();
		try {
			client.query(message);
			ClaudeExecutionResult observed = new ClaudeExecutionResult();
			PhaseCapture capture = SessionLogParser
				.parse(new TeeingIterator(client.receiveResponse(), observed::accept), "session-prompt", message);

			AgentResponse result = observed.response(null, Duration.between(startTime, Instant.now()), null, null);
			result.getMetadata().put("phaseCapture", capture);
			result.getMetadata().put("inputTokens", capture.inputTokens());
			result.getMetadata().put("outputTokens", capture.outputTokens());

			this.lastActivity = Instant.now();
			return result;
		}
		catch (MessageStreamIterator.StreamException ex) {
			this.status = AgentSessionStatus.DEAD;
			throw new IllegalStateException("Session " + sessionId + " transport died", ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Session " + sessionId + " prompt failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public AgentSession resume() {
		if (status != AgentSessionStatus.DEAD) {
			throw new IllegalStateException("Can only resume a DEAD session. Current status: " + status);
		}

		logger.info("Resuming session {} in {}", sessionId, workingDirectory);

		try {
			client.close();
		}
		catch (Exception ex) {
			logger.debug("Failed to close dead session {} before resuming", sessionId, ex);
		}

		CLIOptions options = CLIOptions.builder().resume(sessionId).build();
		this.client = ClaudeClient.sync(options)
			.workingDirectory(workingDirectory)
			.timeout(timeout)
			.claudePath(claudePath)
			.hookRegistry(hookRegistry)
			.build();

		try {
			client.connect();
			Iterator<ParsedMessage> response = client.receiveResponse();
			ClaudeExecutionResult.read(response, null);
		}
		catch (Exception ex) {
			this.status = AgentSessionStatus.DEAD;
			throw new IllegalStateException("Failed to resume session " + sessionId, ex);
		}

		this.status = AgentSessionStatus.RESUMED;
		this.lastActivity = Instant.now();

		logger.info("Session {} resumed successfully", sessionId);
		return this;
	}

	@Override
	public AgentSession fork() {
		throw new UnsupportedOperationException("fork() is not yet implemented");
	}

	@Override
	public void close() {
		try {
			client.close();
		}
		catch (Exception ex) {
			logger.debug("Error closing session {} client: {}", sessionId, ex.getMessage());
		}
		this.status = AgentSessionStatus.DEAD;
	}

}
