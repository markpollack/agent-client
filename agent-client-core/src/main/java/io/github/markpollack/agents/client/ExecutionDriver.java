/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

final class ExecutionDriver {

	private final DefaultAgentExecution execution;

	private final ExecutionWork work;

	private final ExecutionRuntime runtime;

	final ExecutionObservations observations;

	private final ConcurrentLinkedQueue<Runnable> timers = new ConcurrentLinkedQueue<>();

	private final AtomicBoolean cleanupArmed = new AtomicBoolean();

	ExecutionDriver(DefaultAgentExecution execution, ExecutionWork work, ExecutionRuntime runtime) {
		this.execution = execution;
		this.work = work;
		this.runtime = runtime;
		this.observations = new ExecutionObservations(execution, work, runtime);
	}

	void submit() {
		try {
			if (this.execution.store.timeoutNanos > 0)
				arm(this.execution.store.timeoutNanos);
			this.runtime.lanes.worker().execute(() -> ExecutionContext.run(this.execution.store.session, this::run));
		}
		catch (RejectedExecutionException ex) {
			this.execution.store.fail(ExecutionFailure.of(FailureCode.SCHEDULING, ex));
		}
		advance();
	}

	private void arm(long nanos) {
		Runnable cancel = this.runtime.timer.schedule(this::advance, Math.max(0, nanos));
		this.timers.add(cancel);
		if (this.execution.store.state.termination() != null)
			cancel.run();
	}

	private boolean start() {
		var store = this.execution.store;
		store.session.lock.lock();
		try {
			store.expire();
			if (store.state.decision() != null)
				return false;
			store.started = true;
			store.worker = Thread.currentThread();
			store.setup = true;
			store.setupStart = this.runtime.clock.getAsLong();
			return true;
		}
		finally {
			store.session.lock.unlock();
		}
	}

	private void run() {
		if (!start()) {
			advance();
			return;
		}
		try {
			prepare();
			if (!this.execution.isDone())
				process();
		}
		catch (Throwable ex) {
			fail(FailureCode.SETUP_FAILED, ex);
		}
		finally {
			try {
				finishEphemeral();
			}
			catch (RuntimeException ex) {
				fail(FailureCode.CLIENT_PROCESSING, ex);
			}
			this.execution.store.workerFinished();
			advance();
		}
	}

	private void prepare() {
		var store = this.execution.store;
		arm(this.runtime.setupNanos);
		if (store.session.connection == null) {
			try {
				store.session.install(store.session.provider.open(this.work.options(), store.session.retained()));
			}
			catch (ConnectionAcquisitionException partial) {
				store.session.install(partial.connection());
				throw partial;
			}
		}
		String oldConnection = store.session.connectionId;
		if (this.work.replacement() != null && !this.execution.isDone()) {
			var options = this.work.replacement().apply(store.session.options, false);
			store.session.connection.refresh(options);
			store.session.options = options;
			store.session.binding = this.work.replacement();
		}
		AgentExecutionProvider.Turn turn = store.session.connection.prepare(this.execution.executionId(),
				this.observations::progress);
		store.session.connectionId = store.session.connection.connectionId().orElse(store.session.connectionId);
		store.session.lock.lock();
		try {
			store.expire();
			store.setup = false;
			store.reconnected = oldConnection != null && !oldConnection.equals(store.session.connectionId);
			store.turn = java.util.Objects.requireNonNull(turn, "turn");
			store.refresh();
		}
		finally {
			store.session.lock.unlock();
		}
		advance();
	}

	private void finishEphemeral() {
		var store = this.execution.store;
		if (store.session.retained())
			return;
		store.session.lock.lock();
		try {
			store.beginCleanup();
		}
		finally {
			store.session.lock.unlock();
		}
		advance();
		store.session.finishEphemeral();
	}

	private void process() {
		AgentClientResponse result;
		try {
			result = this.work.invoke().apply(this::call);
		}
		catch (TransportFailure ex) {
			fail(FailureCode.PROVIDER_TRANSPORT, ex.getCause());
			return;
		}
		catch (Throwable ex) {
			fail(FailureCode.ADVISOR, ex);
			return;
		}
		try {
			result = ResponseCopies.copy(result);
		}
		catch (Throwable ex) {
			fail(FailureCode.RESULT_COPY, ex);
			return;
		}
		try {
			finishEphemeral();
		}
		catch (RuntimeException ex) {
			fail(FailureCode.CLIENT_PROCESSING, ex);
			return;
		}
		this.execution.store.complete(result);
	}

	private AgentResponse call(AgentTaskRequest request) {
		var store = this.execution.store;
		store.session.lock.lock();
		try {
			store.expire();
			if (store.state.decision() != null)
				throw new CancellationException("Stopped before dispatch");
			store.dispatched = true;
			store.refresh();
		}
		finally {
			store.session.lock.unlock();
		}
		try {
			var response = store.turn.call(request);
			store.observeProvider(response);
			return response;
		}
		catch (Throwable ex) {
			throw new TransportFailure(ex);
		}
	}

	private void fail(FailureCode code, Throwable failure) {
		this.execution.store.fail(ExecutionFailure.of(code, failure));
		advance();
	}

	void advance() {
		var store = this.execution.store;
		Runnable stop = null;
		ExecutionTermination termination = null;
		ExecutionDecision decision = null;
		boolean armCleanup;
		store.session.lock.lock();
		try {
			store.expire();
			if (store.state.decision() != null && !store.started)
				store.workerDone = true;
			stop = claimStop(store);
			store.settle();
			armCleanup = store.cleanupStarted && store.state.termination() == null
					&& this.cleanupArmed.compareAndSet(false, true);
			if (store.state.termination() != null && !store.published) {
				store.published = true;
				termination = store.state.termination();
			}
			if (store.state.decision() != null && !store.observationsClaimed) {
				store.observationsClaimed = true;
				decision = store.state.decision();
			}
		}
		finally {
			store.session.lock.unlock();
		}
		interruptWorker();
		dispatch(stop, termination, decision);
		if (armCleanup)
			arm(this.runtime.cleanupNanos - (this.runtime.clock.getAsLong() - store.cleanupStart));
	}

	private Runnable claimStop(ExecutionStore store) {
		if (!store.stopping || store.turn == null || store.stopClaimed)
			return null;
		store.stopClaimed = true;
		return stopAction(store.turn, store.interrupt);
	}

	private void interruptWorker() {
		var store = this.execution.store;
		store.session.lock.lock();
		try {
			// Ordered with worker deregistration: a delayed stop cannot interrupt a
			// reused thread.
			if (store.interrupt && store.worker != null && !store.workerDone)
				store.worker.interrupt();
		}
		finally {
			store.session.lock.unlock();
		}
	}

	private Runnable stopAction(AgentExecutionProvider.Turn turn, boolean interrupt) {
		return () -> {
			try {

				turn.stop(interrupt);
				var store = this.execution.store;
				store.session.lock.lock();
				try {
					store.stopDone = true;
					store.settle();
				}
				finally {
					store.session.lock.unlock();
				}
			}
			finally {
				advance();
			}
		};
	}

	private void dispatch(Runnable stop, ExecutionTermination termination, ExecutionDecision decision) {
		if (stop != null) {
			try {
				this.runtime.lanes.stop().execute(() -> ExecutionContext.run(this.execution.store.session, stop));
			}
			catch (RejectedExecutionException ignored) {
				/* Original cleanup clock will freeze UNKNOWN. */ }
		}
		if (termination != null) {
			this.timers.forEach(Runnable::run);
			this.timers.clear();
			this.runtime.lanes.publication().execute(() -> this.execution.store.terminationValue.complete(termination));

		}
		if (decision != null) {
			try {
				this.observations.accept(decision);
			}
			finally {
				this.execution.store.session.endOptional();
			}
		}
		this.execution.store.session.afterTermination();
	}

	private static final class TransportFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		TransportFailure(Throwable cause) {
			super(cause);
		}

	}

}
