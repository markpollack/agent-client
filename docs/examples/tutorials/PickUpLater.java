/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.AgentSession;
import io.github.markpollack.agents.client.Capability;
import io.github.markpollack.agents.client.ExecutionTermination;
import io.github.markpollack.agents.client.ProviderReference;

/**
 * Tutorial 4: picking a conversation up later. In the same JVM, a conversation survives a dead
 * provider process or a cancelled turn: the next turn reconnects to the same history. Across a
 * restart, a conversation can be loaded only where the provider supports it; otherwise the
 * load is refused and nothing is replayed or silently started fresh.
 */
public final class PickUpLater {

	private PickUpLater() {
	}

	/**
	 * After a dead process or a cancellation there is nothing to call first: send the next
	 * turn. A retained session reconnects to its native history, keeps its id and reports the
	 * reconnect on the turn's termination.
	 */
	public static ExecutionTermination nextTurn(AgentSession conversation, String text) throws Exception {
		AgentExecution turn = conversation.goal(text).runAsync();
		turn.result();
		return turn.awaitTermination(Duration.ofSeconds(10));
	}

	/** Before shutdown, keep the provider's reference to the native history, once there is one. */
	public static Optional<ProviderReference> reference(AgentSession conversation) {
		return conversation.providerReference();
	}

	/**
	 * After a restart: load the saved conversation only if the provider says it can.
	 * Otherwise refuse, with the provider's reason. Never fall back to a fresh conversation
	 * under the old id: if the application starts a new one, it says so.
	 */
	public static AgentSession load(AgentClient client, AgentExecutionProvider provider, ProviderReference saved,
			Path workspace) {
		Capability load = provider.capabilities().load();
		if (!load.supported())
			throw new UnsupportedOperationException(
					"Cannot load " + saved.nativeId() + " after a restart: " + load.reason().orElse("unsupported"));
		return client.sessions().load(saved, spec -> spec.workingDirectory(workspace));
	}

}
