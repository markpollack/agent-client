/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * Opt-in transport seam for the bounded Client lifecycle. Native adapters must explicitly
 * implement this contract before using async or retained Client sessions. The caller
 * lends the provider; Client owns each opened connection.
 */
public interface AgentExecutionProvider extends AgentApi {

	/** Open without sending a task. Returning transfers a ready connection to Client. */
	Connection open(AgentOptions options);

	@Override
	default AgentResponse call(AgentTaskRequest request) {
		throw new UnsupportedOperationException("Use AgentClient to own the execution lifecycle");
	}

	interface Connection extends AutoCloseable {

		/** Create an exact turn target without dispatching provider work. */
		Turn prepare(String executionId);

		/** Return only when connection resources have ceased. */
		@Override
		void close();

	}

	interface Turn {

		/**
		 * Dispatch once. Return/throw only when required provider processing for this
		 * turn has ceased. An earlier stop must prevent subsequent dispatch. Never replay
		 * work.
		 */
		AgentResponse call(AgentTaskRequest request);

		/** Request cessation of this turn only, even if a successor has since started. */
		void stop(boolean mayInterruptIfRunning);

	}

}
