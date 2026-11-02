/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;
import java.util.concurrent.*;

/** Register before I/O; late resources remain attached to the closing setup ticket. */
final class SessionAcquisition {

	private SessionAcquisition() {
	}

	static AgentSession open(DefaultAgentSession session) {
		var result = new CompletableFuture<Void>();
		long started = session.client.runtime.clock.getAsLong();
		session.beginAcquisition();
		try {
			session.client.runtime.acquisition
				.execute(() -> ExecutionContext.run(session, () -> acquire(session, result, started)));
		}
		catch (RuntimeException failure) {
			session.endAcquisition();
			result.completeExceptionally(failure);
		}
		try {
			result.get(session.client.runtime.setupNanos, TimeUnit.NANOSECONDS);
			if (session.inspect().state() != SessionState.READY)
				throw new AgentOperationException(OperationFailure.CLOSED, "Client closed during acquisition", null,
						Optional.of(session.cleanup()));
			return session;
		}
		catch (InterruptedException failure) {
			return failed(session, OperationFailure.INTERRUPTED, failure, true);
		}
		catch (TimeoutException failure) {
			return failed(session, OperationFailure.SETUP_TIMEOUT, failure, false);
		}
		catch (ExecutionException failure) {
			return failed(session, failure.getCause() instanceof TimeoutException ? OperationFailure.SETUP_TIMEOUT
					: OperationFailure.SETUP_FAILED, failure.getCause(), false);
		}
	}

	private static void acquire(DefaultAgentSession session, CompletableFuture<Void> result, long started) {
		Throwable failure = null;
		try {
			session.install(session.provider.open(session.options, true));
			if (ExecutionTimes.elapsed(session.client.runtime.clock.getAsLong(), started,
					session.client.runtime.setupNanos))
				throw new TimeoutException("Session setup deadline exceeded");
		}
		catch (Throwable ex) {
			if (ex instanceof ConnectionAcquisitionException partial)
				session.install(partial.connection());
			failure = ex;
		}
		finally {
			session.endAcquisition();
		}
		if (failure == null)
			result.complete(null);
		else
			result.completeExceptionally(failure);
	}

	private static AgentSession failed(DefaultAgentSession session, OperationFailure code, Throwable cause,
			boolean interrupted) {
		session.requestClose(new CleanupBudget(session.client.runtime.cleanupNanos));
		try {
			session.awaitClose();
		}
		catch (CleanupUnconfirmedException uncertainty) {
			cause.addSuppressed(uncertainty);
		}
		finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
		throw new AgentOperationException(code, "Session acquisition failed: " + code, cause,
				Optional.of(session.cleanup()));
	}

}
