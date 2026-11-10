/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.AgentSession;

/**
 * Tutorial 1: one server, many conversations. One {@link AgentClient} serves the whole JVM.
 * Each conversation is a retained {@link AgentSession}; the application keeps the map from its
 * own conversation id to the session, and a later HTTP request continues a conversation by
 * that id.
 */
public final class ConversationServer implements AutoCloseable {

	private final AgentClient client;

	private final Map<String, AgentSession> conversations = new ConcurrentHashMap<>();

	/** Create once at startup. The Client runs each execution on its own virtual thread. */
	public ConversationServer(AgentExecutionProvider provider) {
		this.client = AgentClient.builder(provider).defaultTimeout(Duration.ofMinutes(5)).build();
	}

	/** Starts a conversation in its own workspace and returns its id. */
	public String start(Path workspace) {
		AgentSession conversation = this.client.sessions().create(spec -> spec.workingDirectory(workspace));
		this.conversations.put(conversation.id(), conversation);
		return conversation.id();
	}

	/**
	 * A later HTTP request: find the conversation by id and continue it. An unknown id is an
	 * error; it never silently becomes a new conversation.
	 */
	public AgentExecution send(String conversationId, String text) {
		AgentSession conversation = this.conversations.get(conversationId);
		if (conversation == null)
			throw new NoSuchElementException("No conversation " + conversationId);
		return conversation.goal(text).runAsync();
	}

	/** Ends one conversation and releases its provider process. */
	public void end(String conversationId) {
		AgentSession conversation = this.conversations.remove(conversationId);
		if (conversation != null)
			conversation.close();
	}

	/** At shutdown: closing the Client closes every conversation it still owns. */
	@Override
	public void close() {
		this.client.close();
	}

	/** Live Claude Code (not run by the build): {@code ConversationServer <workspace>}. */
	public static void main(String[] args) throws Exception {
		Path workspace = Path.of(args[0]);
		var provider = ClaudeClientProvider.builder().workingDirectory(workspace).build();
		try (var server = new ConversationServer(provider)) {
			String id = server.start(workspace);
			System.out.println(server.send(id, "What does this project build?").result().getResult());
			System.out.println(server.send(id, "Which module would you change first?").result().getResult());
		}
	}

}
