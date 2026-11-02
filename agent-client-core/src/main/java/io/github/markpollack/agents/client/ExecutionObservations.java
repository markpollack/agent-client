/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded serial optional delivery; never calls application code on the provider lane.
 */
final class ExecutionObservations {

	private final DefaultAgentExecution execution;

	private final ExecutionWork work;

	private final ExecutionRuntime runtime;

	final ExecutionRecording recording;

	private final ClassLoader callbackLoader = Thread.currentThread().getContextClassLoader();

	private final ArrayDeque<AgentClientEvent> queue = new ArrayDeque<>();

	private boolean draining;

	private boolean terminal;

	private long sequence;

	private long dropped;

	private long discarded;

	private long failed;

	private Optional<String> lastFailure = Optional.empty();

	private Optional<java.time.Instant> running = Optional.empty();

	ExecutionObservations(DefaultAgentExecution execution, ExecutionWork work, ExecutionRuntime runtime) {
		this.execution = execution;
		this.work = work;
		this.runtime = runtime;
		this.recording = new ExecutionRecording(execution, work, runtime);
	}

	void progress(Map<String, Object> data) {
		if (this.work.observer() != null)
			enqueue(EventKind.PROGRESS, ResponseCopies.map(data));
	}

	void accept(ExecutionDecision decision) {
		if (this.work.observer() != null)
			enqueue(EventKind.OUTCOME, Map.of("decisionId", decision.decisionId()));
		this.recording.accept(decision);
	}

	private void enqueue(EventKind kind, Map<String, Object> data) {
		synchronized (this.queue) {
			if (this.terminal)
				return;
			if (kind == EventKind.OUTCOME)
				this.terminal = true;
			if (this.queue.size() == 64) {
				this.dropped++;
				if (kind == EventKind.PROGRESS) {
					publish();
					return;
				}
				this.queue.removeFirst();
			}
			this.queue.addLast(new AgentClientEvent(this.execution.executionId(), this.execution.sessionId(),
					this.sequence++, this.runtime.wall.get(), kind, data));
			publish();
			if (this.draining)
				return;
			this.draining = true;
		}
		schedule();
	}

	private void schedule() {
		this.execution.store.session.beginOptional();
		try {
			this.runtime.lanes.observer()
				.execute(() -> ExecutionContext.run(this.execution.store.session, this::drain));
		}
		catch (RejectedExecutionException ignored) {
			synchronized (this.queue) {
				this.dropped += this.queue.size();
				this.queue.clear();
				this.draining = false;
				publish();
			}
			this.execution.store.session.endOptional();
		}
	}

	private void drain() {
		var thread = Thread.currentThread();
		var previous = thread.getContextClassLoader();
		thread.setContextClassLoader(this.callbackLoader);
		try {
			AgentClientEvent event;
			while ((event = next()) != null)
				notifyObserver(event);
		}
		finally {
			thread.setContextClassLoader(previous);
			this.execution.store.session.endOptional();
		}
	}

	private AgentClientEvent next() {
		synchronized (this.queue) {
			this.running = Optional.empty();
			if (this.execution.store.session.isClosing()) {
				this.discarded += this.queue.size();
				this.queue.clear();
			}
			var event = this.queue.pollFirst();
			if (event == null)
				this.draining = false;
			else
				this.running = Optional.of(this.runtime.wall.get());
			publish();
			return event;
		}
	}

	private void notifyObserver(AgentClientEvent event) {
		try {
			this.work.observer().accept(event);
		}
		catch (RuntimeException ex) {
			synchronized (this.queue) {
				this.failed++;
				this.lastFailure = Optional.of(ex.getClass().getName());
			}
		}
	}

	private void publish() {
		this.execution.store.callbacks(new CallbackSnapshot(this.running, this.queue.size(), this.dropped,
				this.discarded, this.failed, this.lastFailure));
	}

}
