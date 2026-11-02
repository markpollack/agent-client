/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;

/**
 * All transitions and session admission share session.lock; user code never runs in it.
 */
final class ExecutionStore {

	final DefaultAgentExecution execution;

	final DefaultAgentSession session;

	final ExecutionRuntime runtime;

	final Condition changed;

	final long acceptedNanos;

	final long timeoutNanos;

	final CompletableFuture<ExecutionTermination> terminationValue = new CompletableFuture<>();

	final CompletionStage<ExecutionTermination> terminationStage = this.terminationValue.minimalCompletionStage();

	volatile ExecutionState state;

	// Remaining fields are guarded by session.lock.
	boolean started;

	boolean workerDone;

	boolean dispatched;

	boolean setup;

	long setupStart;

	boolean stopping;

	long cleanupStart;

	boolean interrupt;

	boolean stopClaimed;

	boolean stopDone;

	boolean published;

	boolean observationsClaimed;

	AgentExecutionProvider.Turn turn;

	Thread worker;

	private final ExecutionFailure timeoutFailure;

	private final ExecutionFailure setupFailure;

	ExecutionStore(DefaultAgentExecution execution, DefaultAgentSession session, ExecutionWork work,
			ExecutionRuntime runtime) {
		this.execution = execution;
		this.session = session;
		this.runtime = runtime;
		this.changed = session.lock.newCondition();
		this.acceptedNanos = runtime.clock.getAsLong();
		this.timeoutNanos = work.timeout() == null ? 0 : ExecutionTimes.nanos(work.timeout(), true);
		this.timeoutFailure = ExecutionFailure.of(FailureCode.TIMEOUT,
				new TimeoutException("Execution deadline exceeded"));
		this.setupFailure = ExecutionFailure.of(FailureCode.SETUP_TIMEOUT,
				new TimeoutException("Setup deadline exceeded"));
		this.state = new ExecutionState(null, null, null, null, snapshot(null, null));
	}

	ExecutionState await(boolean termination, long budget) throws InterruptedException {
		ExecutionState observed = this.state;
		if (ready(observed, termination) || budget == 0)
			return observed;
		this.session.lock.lockInterruptibly();
		try {
			long remaining = budget;
			while (!ready(this.state, termination) && remaining > 0)
				remaining = this.changed.awaitNanos(remaining);
			return this.state;
		}
		finally {
			this.session.lock.unlock();
		}
	}

	private static boolean ready(ExecutionState value, boolean termination) {
		return termination ? value.termination() != null : value.decision() != null;
	}

	boolean cancel(boolean mayInterrupt, Reason reason) {
		this.session.lock.lock();
		try {
			expire();
			if (this.state.decision() != null)
				return false;
			this.interrupt = mayInterrupt;
			elect(new ExecutionOutcome.Cancelled(reason), null, null);
			return true;
		}
		finally {
			this.session.lock.unlock();
		}
	}

	void fail(ExecutionFailure failure) {
		this.session.lock.lock();
		try {
			expire();
			if (this.state.decision() == null)
				elect(new ExecutionOutcome.Failed(failure.info()), failure, null);
		}
		finally {
			this.session.lock.unlock();
		}
	}

	void complete(AgentClientResponse response) {
		this.session.lock.lock();
		try {
			expire();
			this.workerDone = true;
			if (this.state.decision() == null) {
				ExecutionOutcome outcome = response.isSuccessful() ? new ExecutionOutcome.Success()
						: new ExecutionOutcome.Stopped(new Reason("STOP", "Provider returned a non-success response",
								"provider", this.runtime.wall.get()));
				elect(outcome, null, response);
			}
			settle();
		}
		finally {
			this.session.lock.unlock();
		}
	}

	void workerFinished() {
		this.session.lock.lock();
		try {
			this.workerDone = true;
			this.worker = null;
			this.setup = false;
			settle();
		}
		finally {
			this.session.lock.unlock();
		}
	}

	void expire() {
		if (this.state.decision() != null)
			return;
		long now = this.runtime.clock.getAsLong();
		boolean executionExpired = this.timeoutNanos > 0
				&& ExecutionTimes.elapsed(now, this.acceptedNanos, this.timeoutNanos);
		boolean setupExpired = this.setup && ExecutionTimes.elapsed(now, this.setupStart, this.runtime.setupNanos);
		if (!executionExpired && !setupExpired)
			return;
		// Compare relative deadlines. Equality belongs to the execution clock.
		long setupOffset = this.setupStart - this.acceptedNanos;
		boolean setupEarlier = setupExpired
				&& (!executionExpired || this.runtime.setupNanos < this.timeoutNanos - setupOffset);
		ExecutionFailure failure = setupEarlier ? this.setupFailure : this.timeoutFailure;
		this.interrupt = true;
		elect(new ExecutionOutcome.Failed(failure.info()), failure, null);
	}

	private void elect(ExecutionOutcome outcome, ExecutionFailure failure, AgentClientResponse response) {
		if (response == null) {
			this.stopping = true;
			this.cleanupStart = this.runtime.clock.getAsLong();
		}
		var provider = new ProviderObservation(
				this.dispatched ? ProviderObservation.Kind.NOT_OBSERVED : ProviderObservation.Kind.NOT_DISPATCHED,
				Optional.empty(), OptionalInt.empty());
		var decision = new ExecutionDecision(this.execution, outcome, provider, this.runtime.wall.get());
		// Successful completion releases the reservation before the volatile outcome
		// write.
		if (response != null)
			this.session.release(this, false);
		this.state = new ExecutionState(decision, failure, response, null, snapshot(decision, null));
		this.changed.signalAll();
	}

	void settle() {
		if (this.state.decision() == null)
			return;
		if (this.state.termination() != null) {
			refresh();
			return;
		}
		boolean confirmed = this.workerDone && (!this.stopping || this.turn == null || this.stopDone);
		boolean expired = this.stopping
				&& ExecutionTimes.elapsed(this.runtime.clock.getAsLong(), this.cleanupStart, this.runtime.cleanupNanos);
		if (!confirmed && !expired) {
			refresh();
			return;
		}
		this.session.release(this, !confirmed);
		Instant now = this.runtime.wall.get();
		var termination = new ExecutionTermination(this.execution.executionId(), this.session.id(), now,
				confirmed ? Optional.of(now) : Optional.empty(), confirmed,
				confirmed ? List.of() : List.of("execution-worker-or-stop"), this.dispatched, false,
				this.session.inspect().state(), reason());
		this.state = new ExecutionState(this.state.decision(), this.state.failure(), this.state.response(), termination,
				snapshot(this.state.decision(), termination));
		this.changed.signalAll();
	}

	void refresh() {
		this.state = new ExecutionState(this.state.decision(), this.state.failure(), this.state.response(),
				this.state.termination(), snapshot(this.state.decision(), this.state.termination()));
	}

	private Optional<Reason> reason() {
		if (this.state != null && this.state.decision() != null
				&& this.state.decision().outcome() instanceof ExecutionOutcome.Cancelled c)
			return Optional.of(c.reason());
		return Optional.empty();
	}

	private ExecutionSnapshot snapshot(ExecutionDecision decision, ExecutionTermination termination) {
		Future.State result = Future.State.RUNNING;
		if (decision != null)
			result = resultState(decision.outcome());
		SettlementState settlement = termination == null ? SettlementState.PENDING
				: termination.cleanupConfirmed() ? SettlementState.SETTLED : SettlementState.UNKNOWN;
		CleanupState cleanup = termination == null ? CleanupState.PENDING
				: termination.cleanupConfirmed() ? CleanupState.CONFIRMED : CleanupState.UNKNOWN;
		return new ExecutionSnapshot(this.execution.executionId(), this.session.id(), result, settlement,
				this.dispatched, false,
				new CleanupSnapshot(this.execution.executionId(), cleanup,
						termination == null ? List.of() : termination.unconfirmedResources(), reason()),
				reason(), new CallbackSnapshot(Optional.empty(), 0, 0, 0, 0, Optional.empty()), Map.of(), List.of());
	}

	private static Future.State resultState(ExecutionOutcome outcome) {
		if (outcome instanceof ExecutionOutcome.Failed)
			return Future.State.FAILED;
		if (outcome instanceof ExecutionOutcome.Cancelled)
			return Future.State.CANCELLED;
		return Future.State.SUCCESS;
	}

}
