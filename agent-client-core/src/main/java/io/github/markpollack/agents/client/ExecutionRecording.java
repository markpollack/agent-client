/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * One bounded secondary delivery attempt. Native acquisition and receiver retries are
 * separate.
 */
final class ExecutionRecording {

	private static final long STALE_NANOS = Duration.ofSeconds(30).toNanos();

	private final DefaultAgentExecution execution;

	private final ExecutionRuntime runtime;

	private final Function<ExecutionDecision, String> recorder;

	private final Object lock = new Object();

	private final String attemptId = UUID.randomUUID().toString();

	private volatile long started;

	private Runnable cancelTimer = () -> {
	};

	private boolean ended;

	volatile RecordingSnapshot snapshot;

	ExecutionRecording(DefaultAgentExecution execution, ExecutionWork work, ExecutionRuntime runtime) {
		this.execution = execution;
		this.runtime = runtime;
		this.recorder = work.recorder();
		this.snapshot = snapshot(null, new TerminalDelivery.NotConfigured(), List.of(), 0);
	}

	void accept(ExecutionDecision decision) {
		if (this.recorder == null)
			return;
		synchronized (this.lock) {
			this.started = this.runtime.clock.getAsLong();
			this.snapshot = snapshot(decision, new TerminalDelivery.Pending(this.runtime.wall.get()), List.of(), 1);
		}
		this.cancelTimer = this.runtime.timer.schedule(() -> stale(decision), STALE_NANOS);
		try {
			this.runtime.lanes.recorder().execute(() -> record(decision));
		}
		catch (RejectedExecutionException ex) {
			finish(decision, failed(FailureCode.RECORDING_FAILED, ex, false));
		}
	}

	private void record(ExecutionDecision decision) {
		synchronized (this.lock) {
			if (this.ended)
				return;
			if (staleNow()) {
				stale(decision);
				return;
			}
			this.snapshot = snapshot(decision,
					new TerminalDelivery.Processing(this.attemptId, 1, this.runtime.wall.get()), List.of(), 2);
		}
		try {
			String binding = Objects.requireNonNull(this.recorder.apply(decision), "receiver bindingId");
			if (binding.isBlank())
				throw new IllegalArgumentException("Empty receiver bindingId");
			finish(decision, new TerminalDelivery.Completed(binding, this.runtime.wall.get(), staleNow()));
		}
		catch (RuntimeException ex) {
			finish(decision, failed(FailureCode.RECORDING_FAILED, ex, true));
		}
	}

	private boolean staleNow() {
		return ExecutionTimes.elapsed(this.runtime.clock.getAsLong(), this.started, STALE_NANOS);
	}

	private void stale(ExecutionDecision decision) {
		var failure = failed(FailureCode.RECORDING_TIMEOUT, new TimeoutException("Recording observation expired"),
				true);
		synchronized (this.lock) {
			if (this.ended)
				return;
			replace(decision, failure);
		}
	}

	private void finish(ExecutionDecision decision, TerminalDelivery delivery) {
		synchronized (this.lock) {
			this.ended = true;
			// A delayed clock callback must not erase the staleness observation before a
			// late ACK.
			if (staleNow() && !(this.snapshot.delivery() instanceof TerminalDelivery.Failed))
				replace(decision, failed(FailureCode.RECORDING_TIMEOUT,
						new TimeoutException("Recording observation expired"), true));
			replace(decision, delivery);
		}
		this.cancelTimer.run();
	}

	private void replace(ExecutionDecision decision, TerminalDelivery delivery) {
		var attempts = new ArrayList<>(this.snapshot.attempts());
		attempts.add(new DeliveryAttempt(this.attemptId, 1, decision.acceptedAt(), Optional.of(this.runtime.wall.get()),
				delivery));
		this.snapshot = snapshot(decision, delivery, attempts, this.snapshot.revision() + 1);
	}

	private static TerminalDelivery failed(FailureCode code, Throwable failure, boolean unknown) {
		return new TerminalDelivery.Failed(ExecutionFailure.of(code, failure).info(), false, unknown);
	}

	private RecordingSnapshot snapshot(ExecutionDecision decision, TerminalDelivery delivery,
			List<DeliveryAttempt> attempts, long revision) {
		return new RecordingSnapshot(this.execution.executionId(), revision, this.runtime.wall.get(),
				Optional.ofNullable(decision).map(ExecutionDecision::decisionId), evidence(decision), delivery,
				attempts);
	}

	private CaptureReceipt evidence(ExecutionDecision decision) {
		var count = new ReportedCount(OptionalLong.empty(), MetricScope.UNKNOWN, "not-reported");
		var provider = decision == null
				? new ProviderObservation(ProviderObservation.Kind.NOT_OBSERVED, Optional.empty(), OptionalInt.empty())
				: decision.provider();
		return new CaptureReceipt(this.execution.executionId(), this.execution.sessionId(), Optional.empty(), count,
				new ReportedCost(Optional.empty(), MetricScope.UNKNOWN, "not-reported"),
				new TokenCounts(count, count, count, count, count), provider, CaptureAcquisition.UNAVAILABLE,
				"decision-only", "unavailable", Optional.empty(),
				List.of("Native capture is not implemented by this lifecycle seam"));
	}

}
