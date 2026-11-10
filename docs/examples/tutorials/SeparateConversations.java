/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentSession;
import io.github.markpollack.agents.client.ExecutionTermination;

/**
 * Tutorial 3: separate conversations run independently. Alice and Nancy share one
 * conversation; Bob and Doug share another. Their turns run at the same time, and cancelling
 * one conversation's turn leaves the other running.
 */
public final class SeparateConversations {

	private SeparateConversations() {
	}

	/** The two conversations, each in its own workspace. */
	public record Pair(AgentSession aliceAndNancy, AgentSession bobAndDoug) {
	}

	/**
	 * Separate histories do not isolate file writes: two conversations in one directory can
	 * overwrite each other's files. Give each conversation its own workspace.
	 */
	public static Pair open(AgentClient client, Path workspaces) throws Exception {
		Path first = Files.createDirectories(workspaces.resolve("alice-and-nancy"));
		Path second = Files.createDirectories(workspaces.resolve("bob-and-doug"));
		return new Pair(client.sessions().create(spec -> spec.workingDirectory(first)),
				client.sessions().create(spec -> spec.workingDirectory(second)));
	}

	/**
	 * Cancel one conversation's turn. Only that turn stops; the other conversation's turn is
	 * not touched and its result arrives as usual.
	 * @return the cancelled turn's termination
	 */
	public static ExecutionTermination cancel(AgentExecution turn) throws Exception {
		turn.cancel(true);
		return turn.awaitTermination(Duration.ofSeconds(10));
	}

}
