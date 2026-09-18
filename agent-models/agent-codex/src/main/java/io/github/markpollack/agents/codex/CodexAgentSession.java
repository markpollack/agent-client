/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.AgentSessionStatus;

/**
 * A semantic Codex conversation. Its stable registry ID is distinct from the provider
 * thread ID learned on the first real prompt. Every later prompt resumes that exact
 * thread with the original configuration. Close is terminal; cancellation interrupts only
 * this turn's zt-exec wait and direct child, not application-owned handlers.
 */
public final class CodexAgentSession implements AgentSession {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final String id = UUID.randomUUID().toString();

	private final Path directory;

	private final CodexClient client;

	private final Object lifecycle = new Object();

	private volatile Instant lastActivity = Instant.now();

	private volatile AgentSessionStatus status = AgentSessionStatus.ACTIVE;

	private String threadId;

	private Thread activeThread;

	private boolean closed;

	private boolean cancelled;

	private boolean finishing;

	CodexAgentSession(Path directory, CodexClient client) {
		this.directory = directory;
		this.client = client;
	}

	@Override
	public String getSessionId() {
		return id;
	}

	@Override
	public Path getWorkingDirectory() {
		return directory;
	}

	@Override
	public AgentSessionStatus getStatus() {
		return status;
	}

	/**
	 * @return last creation, turn completion or resume time
	 */
	public Instant getLastActivity() {
		return lastActivity;
	}

	@Override
	public AgentResponse prompt(String message) {
		return prompt(message, event -> {
		});
	}

	@Override
	public AgentResponse prompt(String message, Consumer<AgentSessionEvent> observer) {
		Objects.requireNonNull(observer, "observer");
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("Prompt must not be blank");
		}
		String resumeId;
		synchronized (lifecycle) {
			if (activeThread != null) {
				throw new IllegalStateException("A conversation turn is already active");
			}
			if (closed || status == AgentSessionStatus.DEAD) {
				throw new IllegalStateException("Conversation is closed or dead; resume is required");
			}
			activeThread = Thread.currentThread();
			cancelled = false;
			finishing = false;
			resumeId = threadId;
		}
		var fold = new Turn(observer);
		var outcome = AgentSessionEvent.Outcome.ERROR;
		try {
			int exit = client.stream(resumeId, message, fold::accept);
			synchronized (lifecycle) {
				checkCancellation();
				if (exit != 0 || fold.failed) {
					throw new IllegalStateException(
							"Codex CLI rejected or failed the conversation/MCP configuration or turn");
				}
				if (!fold.completed || !fold.sawThread || threadId == null) {
					throw new IllegalStateException(
							"Codex CLI lacks the required conversation event/identity capability");
				}
				outcome = AgentSessionEvent.Outcome.SUCCESS;
				finishing = true;
			}
			return new AgentResponse(
					List.of(new AgentGeneration(fold.text.toString(),
							new AgentGenerationMetadata(outcome.name(), Map.of()))),
					AgentResponseMetadata.builder()
						.sessionId(id)
						.providerFields(Map.of("providerThreadId", threadId))
						.build());
		}
		catch (RuntimeException ex) {
			synchronized (lifecycle) {
				status = AgentSessionStatus.DEAD;
				finishing = true;
				if (cancelled) {
					outcome = AgentSessionEvent.Outcome.CANCELLED;
				}
			}
			if (outcome == AgentSessionEvent.Outcome.CANCELLED) {
				throw new CancellationException("Codex turn cancelled");
			}
			throw ex;
		}
		finally {
			// Keep the turn reserved while the terminal observer runs, rejecting reentry.
			try {
				observer.accept(new AgentSessionEvent.Terminal(outcome));
			}
			catch (RuntimeException ex) {
				status = AgentSessionStatus.DEAD;
				throw ex;
			}
			finally {
				synchronized (lifecycle) {
					activeThread = null;
					lastActivity = Instant.now();
					// Do not leave an adapter-generated interrupt on an executor's
					// worker.
					if (cancelled) {
						Thread.interrupted();
					}
				}
			}
		}
	}

	private void checkCancellation() {
		if (cancelled) {
			throw new CancellationException("Codex turn cancelled");
		}
	}

	private final class Turn {

		private final Consumer<AgentSessionEvent> observer;

		private final StringBuilder text = new StringBuilder();

		private boolean completed;

		private boolean failed;

		private boolean sawThread;

		Turn(Consumer<AgentSessionEvent> observer) {
			this.observer = observer;
		}

		void accept(String line) {
			synchronized (lifecycle) {
				checkCancellation();
			}
			// CLI diagnostics are not JSON events. Never forward raw diagnostics or
			// secrets.
			if (!line.stripLeading().startsWith("{")) {
				return;
			}
			JsonNode event;
			try {
				event = JSON.readTree(line);
			}
			catch (JsonProcessingException ex) {
				throw new IllegalStateException("Invalid Codex conversation JSON event");
			}
			String type = event.path("type").asText();
			if ("thread.started".equals(type)) {
				String received = event.path("thread_id").asText();
				synchronized (lifecycle) {
					if (received.isBlank() || (threadId != null && !threadId.equals(received))) {
						throw new IllegalStateException("Codex returned a missing or different thread ID");
					}
					threadId = received;
					sawThread = true;
				}
			}
			else if ("turn.completed".equals(type)) {
				completed = true;
			}
			else if ("turn.failed".equals(type) || "error".equals(type)) {
				failed = true;
			}
			else if (type.startsWith("item.")) {
				JsonNode item = event.path("item");
				String itemType = item.path("type").asText();
				String itemId = item.path("id").asText();
				if ("agent_message".equals(itemType) && "item.completed".equals(type)) {
					String value = item.path("text").asText();
					text.append(value);
					observer.accept(new AgentSessionEvent.Text(value));
				}
				else if ("mcp_tool_call".equals(itemType)) {
					if ("item.started".equals(type)) {
						Map<String, Object> arguments = item.path("arguments").isObject()
								? JSON.convertValue(item.path("arguments"),
										new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
										})
								: Map.of();
						observer.accept(new AgentSessionEvent.ToolCall(itemId,
								item.path("server").asText() + "/" + item.path("tool").asText(), arguments));
					}
					else if ("item.completed".equals(type)) {
						boolean error = "failed".equals(item.path("status").asText())
								|| !item.path("error").isNull() && !item.path("error").isMissingNode()
								|| item.path("result").path("isError").asBoolean(false);
						Object content = JSON.convertValue(error ? item.path("error") : item.path("result"),
								Object.class);
						observer.accept(new AgentSessionEvent.ToolResult(itemId, content, error));
					}
				}
			}
		}

	}

	@Override
	public void cancelActiveTurn() {
		synchronized (lifecycle) {
			if (activeThread == null || cancelled || finishing) {
				return;
			}
			cancelled = true;
			status = AgentSessionStatus.DEAD;
			activeThread.interrupt();
		}
	}

	@Override
	public AgentSession resume() {
		synchronized (lifecycle) {
			if (closed || activeThread != null || status != AgentSessionStatus.DEAD) {
				throw new IllegalStateException("Only an idle, dead, unclosed conversation can resume");
			}
			if (threadId == null) {
				throw new IllegalStateException(
						"Codex has not supplied a thread ID; exact-thread resume is unavailable");
			}
			status = AgentSessionStatus.RESUMED;
			lastActivity = Instant.now();
			return this;
		}
	}

	@Override
	public AgentSession fork() {
		throw new UnsupportedOperationException("Codex conversation forking is unsupported");
	}

	@Override
	public void close() {
		synchronized (lifecycle) {
			if (closed) {
				return;
			}
			closed = true;
			cancelActiveTurn();
			status = AgentSessionStatus.DEAD;
			client.close();
		}
	}

}
