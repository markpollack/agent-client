/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * The owning synchronous call stops its hidden execution when its caller is interrupted.
 */
final class SynchronousExecution {

	private SynchronousExecution() {
	}

	static AgentClientResponse result(DefaultAgentExecution execution) {
		try {
			return execution.result();
		}
		catch (InterruptedException ex) {
			execution.cancel(true);
			long remaining = remaining(execution);
			try {
				execution.awaitTermination(Duration.ofNanos(remaining));
			}
			catch (InterruptedException | TimeoutException cleanup) {
				ex.addSuppressed(cleanup);
			}
			finally {
				Thread.currentThread().interrupt();
			}
			var cancelled = new CancellationException(
					"Synchronous execution interrupted; cleanup=" + execution.inspect().settlement());
			cancelled.initCause(ex);
			throw cancelled;
		}
	}

	private static long remaining(DefaultAgentExecution execution) {
		var store = execution.store;
		store.session.lock.lock();
		try {
			return Math.max(0, store.runtime.cleanupNanos - (store.runtime.clock.getAsLong() - store.cleanupStart));
		}
		finally {
			store.session.lock.unlock();
		}
	}

}
