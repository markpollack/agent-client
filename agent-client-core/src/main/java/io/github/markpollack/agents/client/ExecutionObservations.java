/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;

/** Secondary effects cannot elect an execution outcome or retain its reservation. */
final class ExecutionObservations {

	private final DefaultAgentExecution execution;

	private final ExecutionWork work;

	private final ExecutionRuntime runtime;

	final ExecutionRecording recording;

	private final ClassLoader callbackLoader = Thread.currentThread().getContextClassLoader();

	ExecutionObservations(DefaultAgentExecution execution, ExecutionWork work, ExecutionRuntime runtime) {
		this.execution = execution;
		this.work = work;
		this.runtime = runtime;
		this.recording = new ExecutionRecording(execution, work, runtime);
	}

	void accept(ExecutionDecision decision) {
		if (this.work.observer() != null) {
			this.execution.store.callbacks(new CallbackSnapshot(Optional.empty(), 1, 0, 0, 0, Optional.empty()));
			this.execution.store.session.beginOptional();
			try {
				this.runtime.lanes.observer().execute(() -> ExecutionContext.run(this.execution.store.session, () -> {
					try {
						notifyObserver(decision);
					}
					finally {
						this.execution.store.session.endOptional();
					}
				}));
			}
			catch (RejectedExecutionException ignored) {
				this.execution.store.session.endOptional();
				this.execution.store.callbacks(new CallbackSnapshot(Optional.empty(), 0, 1, 0, 0, Optional.empty()));
			}
		}
		this.recording.accept(decision);
	}

	private void notifyObserver(ExecutionDecision decision) {
		if (this.execution.store.session.isClosing()) {
			this.execution.store.callbacks(new CallbackSnapshot(Optional.empty(), 0, 1, 0, 0, Optional.empty()));
			return;
		}
		var thread = Thread.currentThread();
		var previousLoader = thread.getContextClassLoader();
		thread.setContextClassLoader(this.callbackLoader);
		this.execution.store
			.callbacks(new CallbackSnapshot(Optional.of(this.runtime.wall.get()), 0, 0, 0, 0, Optional.empty()));
		Optional<String> failure = Optional.empty();
		try {
			this.work.observer()
				.accept(new AgentClientEvent(decision.executionId(), decision.sessionId(), 0, decision.acceptedAt(),
						EventKind.OUTCOME, Map.of("decisionId", decision.decisionId())));
		}
		catch (RuntimeException ex) {
			failure = Optional.of(ex.getClass().getName());
		}
		finally {
			thread.setContextClassLoader(previousLoader);
			this.execution.store
				.callbacks(new CallbackSnapshot(Optional.empty(), 0, 0, 0, failure.isPresent() ? 1 : 0, failure));
		}
	}

}
