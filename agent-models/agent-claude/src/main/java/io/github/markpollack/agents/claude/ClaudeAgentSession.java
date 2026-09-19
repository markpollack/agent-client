/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.util.function.BiFunction;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import java.time.Instant;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.AgentSessionStatus;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.UserMessage;
import io.github.markpollack.claude.agent.sdk.types.ContentBlock;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolResultBlock;
import io.github.markpollack.journal.claude.SessionLogParser;

/**
 * One Claude conversation with ordered turns and replaceable scoped MCP configuration.
 * The SDK retains the process between prompts. Cancellation closes the SDK process tree;
 * resume reattaches the same or application-supplied configuration without an extra model
 * prompt. Close is terminal.
 */
public class ClaudeAgentSession implements AgentSession {

	private final String sessionId;

	private final Path workingDirectory;

	private ClaudeSessionConfiguration configuration;

	private final Function<CLIOptions, ClaudeSyncClient> clientFactory;

	private final String scopedServer;

	private final ReentrantLock turn = new ReentrantLock();

	private final BiFunction<McpServerDefinition, java.util.Map<String, String>, ClaudeSessionConfiguration> bindingFactory;

	private final Object lifecycle = new Object();

	private ClaudeSyncClient client;

	private boolean closed;

	private boolean active;

	private boolean cancelled;

	private boolean configurationValidated;

	private volatile AgentSessionStatus status = AgentSessionStatus.ACTIVE;

	private volatile Instant lastActivity = Instant.now();

	ClaudeAgentSession(String id, Path directory, ClaudeSyncClient client, ClaudeSessionConfiguration configuration,
			Function<CLIOptions, ClaudeSyncClient> clientFactory, String scopedServer) {
		this(id, directory, client, configuration, clientFactory, scopedServer, null);
	}

	ClaudeAgentSession(String id, Path directory, ClaudeSyncClient client, ClaudeSessionConfiguration configuration,
			Function<CLIOptions, ClaudeSyncClient> clientFactory, String scopedServer,
			BiFunction<McpServerDefinition, Map<String, String>, ClaudeSessionConfiguration> bindingFactory) {
		this.bindingFactory = bindingFactory;
		this.sessionId = id;
		this.workingDirectory = directory;
		this.client = client;
		this.configuration = configuration;
		this.clientFactory = clientFactory;
		this.scopedServer = scopedServer;
		this.configurationValidated = scopedServer == null;
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
	 * @return last creation, prompt or resume activity
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
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(observer, "observer");
		if (turn.isHeldByCurrentThread() || !turn.tryLock()) {
			throw new IllegalStateException("A conversation turn is already active");
		}
		boolean started = false;
		AgentSessionEvent.Outcome outcome = AgentSessionEvent.Outcome.ERROR;
		Instant start = Instant.now();
		try {
			synchronized (lifecycle) {
				if (closed || status == AgentSessionStatus.DEAD) {
					throw new IllegalStateException(
							"Conversation is closed or dead; a dead conversation requires resume");
				}
				active = true;
				cancelled = false;
				started = true;
				client.query(message, sessionId);
			}
			Iterator<ParsedMessage> source = client.receiveResponse();
			ResultMessage[] result = new ResultMessage[1];
			Iterator<ParsedMessage> observed = new Iterator<>() {
				public boolean hasNext() {
					checkCancellation();
					return source.hasNext();
				}

				public ParsedMessage next() {
					ParsedMessage parsed = source.next();
					checkCancellation();
					if (parsed.asMessage() instanceof ResultMessage terminal) {
						result[0] = terminal;
					}
					observe(parsed, observer);
					return parsed;
				}
			};
			var capture = SessionLogParser.parse(observed, "session-prompt", message);
			synchronized (lifecycle) {
				if (cancelled) {
					throw new CancellationException("Claude turn cancelled");
				}
				if (result[0] == null) {
					throw new IllegalStateException("Claude stream ended without a terminal result");
				}
				if (!sessionId.equals(result[0].sessionId())) {
					throw new IllegalStateException("Claude returned a different conversation ID");
				}
				outcome = result[0].isError() ? AgentSessionEvent.Outcome.ERROR : AgentSessionEvent.Outcome.SUCCESS;
				active = false;
			}
			var metadata = AgentResponseMetadata.builder()
				.sessionId(sessionId)
				.duration(Duration.between(start, Instant.now()))
				.providerFields(Map.of("phaseCapture", capture, "inputTokens", capture.inputTokens(), "outputTokens",
						capture.outputTokens()))
				.build();
			String text = capture.textOutput() == null ? "" : capture.textOutput();
			return new AgentResponse(
					List.of(new AgentGeneration(text, new AgentGenerationMetadata(outcome.name(), Map.of()))),
					metadata);
		}
		catch (Exception ex) {
			if (started) {
				synchronized (lifecycle) {
					status = AgentSessionStatus.DEAD;
					if (cancelled) {
						outcome = AgentSessionEvent.Outcome.CANCELLED;
					}
					client.close();
				}
			}
			if (outcome == AgentSessionEvent.Outcome.CANCELLED) {
				throw new CancellationException("Claude turn cancelled");
			}
			throw new IllegalStateException("Claude conversation turn failed", ex);
		}
		finally {
			try {
				if (started) {
					synchronized (lifecycle) {
						active = false;
					}
					lastActivity = Instant.now();
					try {
						observer.accept(new AgentSessionEvent.Terminal(outcome));
					}
					catch (RuntimeException ex) {
						synchronized (lifecycle) {
							status = AgentSessionStatus.DEAD;
							client.close();
						}
						throw ex;
					}
				}
			}
			finally {
				turn.unlock();
			}
		}
	}

	private void checkCancellation() {
		synchronized (lifecycle) {
			if (cancelled) {
				throw new CancellationException("Claude turn cancelled");
			}
		}
	}

	private void observe(ParsedMessage parsed, Consumer<AgentSessionEvent> observer) {
		if (parsed.asMessage() instanceof SystemMessage system && "init".equals(system.subtype())
				&& scopedServer != null) {
			Object servers = system.data().get("mcp_servers");
			boolean connected = servers instanceof List<?> list && list.stream()
				.anyMatch(value -> value instanceof Map<?, ?> entry && scopedServer.equals(entry.get("name"))
						&& "connected".equals(entry.get("status")));
			configurationValidated = connected;
			if (!connected) {
				throw new IllegalStateException("Claude did not connect the scoped MCP server: " + scopedServer);
			}
		}
		if (!configurationValidated
				&& (parsed.asMessage() instanceof AssistantMessage || parsed.asMessage() instanceof ResultMessage)) {
			throw new IllegalStateException("Claude did not confirm the scoped MCP configuration");
		}
		if (parsed.asMessage() instanceof AssistantMessage assistant) {
			for (ContentBlock block : assistant.content()) {
				observeBlock(block, observer);
			}
		}
		if (parsed.asMessage() instanceof UserMessage user && user.content() instanceof List<?> blocks) {
			for (Object block : blocks) {
				if (block instanceof ContentBlock content) {
					observeBlock(content, observer);
				}
			}
		}
	}

	private void observeBlock(ContentBlock block, Consumer<AgentSessionEvent> observer) {
		if (block instanceof TextBlock text) {
			observer.accept(new AgentSessionEvent.Text(text.text()));
		}
		else if (block instanceof ToolUseBlock tool) {
			observer.accept(new AgentSessionEvent.ToolCall(tool.id(), tool.name(), tool.input()));
		}
		else if (block instanceof ToolResultBlock result) {
			observer.accept(new AgentSessionEvent.ToolResult(result.toolUseId(), result.content(),
					Boolean.TRUE.equals(result.isError())));
		}
	}

	@Override
	public void cancelActiveTurn() {
		synchronized (lifecycle) {
			if (!active || cancelled) {
				return;
			}
			cancelled = true;
			status = AgentSessionStatus.DEAD;
			client.close();
		}
	}

	@Override
	public AgentSession resume() {
		return resumeCurrent(null, null);
	}

	@Override
	public AgentSession resume(McpServerDefinition definition, java.util.Map<String, String> environmentVariables) {
		java.util.Objects.requireNonNull(definition, "definition");
		return resumeCurrent(definition, java.util.Map.copyOf(environmentVariables));
	}

	private AgentSession resumeCurrent(McpServerDefinition definition, java.util.Map<String, String> environment) {
		if (turn.isHeldByCurrentThread() || !turn.tryLock()) {
			throw new IllegalStateException("A turn is still active");
		}
		try {
			synchronized (lifecycle) {
				if (closed || status != AgentSessionStatus.DEAD) {
					throw new IllegalStateException("Only a dead, unclosed conversation can resume");
				}
				if (definition != null) {
					if (bindingFactory == null || scopedServer == null) {
						throw new UnsupportedOperationException("A scoped conversation is required");
					}
					var replacement = bindingFactory.apply(definition, environment);
					try {
						client.close();
						configuration.close();
					}
					catch (RuntimeException ex) {
						replacement.close();
						throw ex;
					}
					configuration = replacement;
				}
				else {
					client.close();
				}
				client = clientFactory.apply(configuration.resumed());
				try {
					client.connect();
				}
				catch (Exception ex) {
					client.close();
					throw new IllegalStateException("Cannot resume Claude conversation", ex);
				}
				configurationValidated = scopedServer == null;
				status = AgentSessionStatus.RESUMED;
				lastActivity = Instant.now();
				return this;
			}
		}
		finally {
			turn.unlock();
		}
	}

	@Override
	public AgentSession fork() {
		throw new UnsupportedOperationException("Conversation forking is unsupported");
	}

	@Override
	public void close() {
		synchronized (lifecycle) {
			if (closed) {
				return;
			}
			closed = true;
			cancelled = active;
			status = AgentSessionStatus.DEAD;
			try {
				client.close();
			}
			finally {
				configuration.close();
			}
		}
	}

}
