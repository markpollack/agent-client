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

	volatile RecordingSnapshot recording;

	ExecutionObservations(DefaultAgentExecution execution, ExecutionWork work, ExecutionRuntime runtime) {
		this.execution = execution;
		this.work = work;
		this.runtime = runtime;
		this.recording = snapshot(null, new TerminalDelivery.NotConfigured(), 0);
	}

	void accept(ExecutionDecision decision) {
		if (this.work.observer() != null) {
			try {
				this.runtime.lanes.observer().execute(() -> notifyObserver(decision));
			}
			catch (RejectedExecutionException ignored) {
				/* Best effort observation, never caller-runs. */ }
		}
		if (this.work.recorder() != null) {
			this.recording = snapshot(decision, new TerminalDelivery.Pending(this.runtime.wall.get()), 1);
			try {
				this.runtime.lanes.recorder().execute(() -> record(decision));
			}
			catch (RejectedExecutionException ex) {
				failed(decision, ex);
			}
		}
	}

	private void notifyObserver(ExecutionDecision decision) {
		try {
			this.work.observer()
				.accept(new AgentClientEvent(decision.executionId(), decision.sessionId(), 0, decision.acceptedAt(),
						EventKind.OUTCOME, Map.of("decisionId", decision.decisionId())));
		}
		catch (RuntimeException ignored) {
			/* Optional observer failure does not change the decision. */ }
	}

	private void record(ExecutionDecision decision) {
		try {
			this.work.recorder().accept(decision);
			this.recording = snapshot(decision,
					new TerminalDelivery.Completed("local-recorder", this.runtime.wall.get(), false), 2);
		}
		catch (RuntimeException ex) {
			failed(decision, ex);
		}
	}

	private void failed(ExecutionDecision decision, Throwable failure) {
		this.recording = snapshot(decision, new TerminalDelivery.Failed(
				ExecutionFailure.of(FailureCode.RECORDING_FAILED, failure).info(), false, true), 2);
	}

	private RecordingSnapshot snapshot(ExecutionDecision decision, TerminalDelivery delivery, long revision) {
		// This seam delivers decisions only; it does not claim native capture or a
		// Journal binding.
		return new RecordingSnapshot(this.execution.executionId(), revision, this.runtime.wall.get(),
				Optional.ofNullable(decision).map(ExecutionDecision::decisionId), evidence(decision), delivery,
				List.of());
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
