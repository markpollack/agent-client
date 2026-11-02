/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

final class DefaultAgentExecution implements AgentExecution {

	private final String id = java.util.UUID.randomUUID().toString();

	final ExecutionStore store;

	final ExecutionDriver driver;

	private final ApplicationCorrelation correlation;

	DefaultAgentExecution(DefaultAgentSession session, ExecutionWork work, ExecutionRuntime runtime) {
		this.correlation = work.correlation();
		this.store = new ExecutionStore(this, session, work, runtime);
		this.driver = new ExecutionDriver(this, work, runtime);
	}

	ApplicationCorrelation correlation() {
		return this.correlation;
	}

	@Override
	public String executionId() {
		return this.id;
	}

	@Override
	public String sessionId() {
		return this.store.session.id();
	}

	@Override
	public boolean cancel(boolean interrupt) {
		return cancel(interrupt,
				new Reason("CANCELLED", "Execution cancelled", "caller", this.store.runtime.wall.get()));
	}

	@Override
	public boolean cancel(boolean interrupt, Reason reason) {
		java.util.Objects.requireNonNull(reason, "reason");
		boolean accepted = this.store.cancel(interrupt, reason);
		this.driver.advance();
		return accepted;
	}

	@Override
	public boolean isDone() {
		return this.store.state.decision() != null;
	}

	@Override
	public boolean isCancelled() {
		var decision = this.store.state.decision();
		return decision != null && decision.outcome() instanceof ExecutionOutcome.Cancelled;
	}

	@Override
	public AgentClientResponse result() throws InterruptedException {
		return deliver(this.store.await(false, Long.MAX_VALUE));
	}

	@Override
	public AgentClientResponse result(Duration timeout) throws InterruptedException, TimeoutException {
		long nanos = ExecutionTimes.nanos(timeout, false);
		ExecutionState state = this.store.await(false, nanos);
		if (state.decision() == null)
			throw new TimeoutException("Caller wait expired");
		return deliver(state);
	}

	private AgentClientResponse deliver(ExecutionState state) {
		if (state.decision().outcome() instanceof ExecutionOutcome.Cancelled cancelled) {
			throw new CancellationException(cancelled.reason().message());
		}
		if (state.failure() != null) {
			throw new AgentExecutionException(state.failure().info().message(), state.failure().cause(),
					state.snapshot(), state.failure().info());
		}
		return ResponseCopies.copy(state.response());
	}

	@Override
	public CompletionStage<ExecutionTermination> termination() {
		return this.store.terminationStage;
	}

	@Override
	public ExecutionTermination awaitTermination(Duration timeout) throws InterruptedException, TimeoutException {
		ExecutionState state = this.store.await(true, ExecutionTimes.nanos(timeout, false));
		if (state.termination() == null)
			throw new TimeoutException("Termination wait expired");
		return state.termination();
	}

	@Override
	public Optional<ExecutionDecision> decision() {
		return Optional.ofNullable(this.store.state.decision());
	}

	@Override
	public RecordingSnapshot recording() {
		return this.driver.observations.recording.snapshot;
	}

	@Override
	public ExecutionSnapshot inspect() {
		return this.store.state.snapshot();
	}

	@Override
	public Optional<AgentClientResponse> responseSnapshot() {
		return Optional.ofNullable(this.store.state.response()).map(ResponseCopies::copy);
	}

}
