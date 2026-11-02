/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;

/** Prevent a required worker or optional callback from waiting for its own teardown. */
final class ExecutionContext {

	private static final ThreadLocal<DefaultAgentSession> CURRENT = new ThreadLocal<>();

	private ExecutionContext() {
	}

	static void run(DefaultAgentSession session, Runnable action) {
		var previous = CURRENT.get();
		CURRENT.set(session);
		try {
			action.run();
		}
		finally {
			if (previous == null)
				CURRENT.remove();
			else
				CURRENT.set(previous);
		}
	}

	static void checkClose(DefaultAgentClient client, DefaultAgentSession session) {
		var current = CURRENT.get();
		if (current != null && current.client.equals(client) && (session == null || current == session))
			throw new AgentOperationException(OperationFailure.SELF_CLOSE,
					"Cannot close from an owned worker or callback", null, Optional.empty());
	}

}
