/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S9: a decision made before or during close is still recorded. Close drains in-flight
 * recordings within the Client's cleanup budget, and reports a recording that outlasts
 * the budget as unconfirmed cleanup instead of dropping it silently.
 */
@Timeout(30)
class RecordingOnCloseTest {

	/** A turn that runs until it is stopped. */
	private static AsyncLifecycleTest.Provider hanging() {
		var provider = new AsyncLifecycleTest.Provider();
		provider.answer = request -> {
			while (provider.stops.isEmpty()) {
				try {
					Thread.sleep(5);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			throw new CancellationException("stopped");
		};
		return provider;
	}

	/**
	 * A recorder whose preparation takes {@code prepare}, as sealing a partial capture
	 * can.
	 */
	private static ExecutionRecorder slow(Duration prepare, List<ExecutionDecision> recorded) {
		return decision -> {
			try {
				Thread.sleep(prepare.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return () -> {
				recorded.add(decision);
				return "ack-" + decision.decisionId();
			};
		};
	}

	private static void awaitCall(AsyncLifecycleTest.Provider provider) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (provider.calls.get() == 0 && System.nanoTime() < end)
			Thread.sleep(2);
		assertThat(provider.calls).hasValue(1);
	}

	@Test
	void cancelThenCloseStillRecordsTheDecision() throws Exception {
		var provider = hanging();
		List<ExecutionDecision> recorded = new CopyOnWriteArrayList<>();
		AgentExecution execution;
		try (var client = AgentClient.builder(provider)
			.executionRecorder(slow(Duration.ofMillis(300), recorded))
			.build()) {
			execution = client.goal("hang").runAsync();
			awaitCall(provider);
			assertThat(execution.cancel(true)).isTrue();
			assertThat(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			// Close at once, without waiting for recording delivery.
		}
		assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
		assertThat(recorded).singleElement()
			.satisfies(d -> assertThat(d.outcome()).isInstanceOf(ExecutionOutcome.Cancelled.class));
	}

	@Test
	void closeWithoutCancelRecordsTheCancelledDecision() throws Exception {
		var provider = hanging();
		List<ExecutionDecision> recorded = new CopyOnWriteArrayList<>();
		AgentExecution execution;
		try (var client = AgentClient.builder(provider)
			.executionRecorder(slow(Duration.ofMillis(100), recorded))
			.build()) {
			execution = client.goal("hang").runAsync();
			awaitCall(provider);
			// Abandon the execution: the close cancels it.
		}
		assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
		assertThat(recorded).singleElement()
			.satisfies(d -> assertThat(d.outcome()).isInstanceOf(ExecutionOutcome.Cancelled.class));
	}

	@Test
	void aRecordingThatOutlastsTheCleanupBudgetIsReportedNotDropped() throws Exception {
		var provider = hanging();
		List<ExecutionDecision> recorded = new CopyOnWriteArrayList<>();
		var client = AgentClient.builder(provider)
			.cleanupTimeout(Duration.ofMillis(300))
			.executionRecorder(slow(Duration.ofSeconds(3), recorded))
			.build();
		var execution = client.goal("hang").runAsync();
		awaitCall(provider);
		execution.cancel(true);
		execution.awaitTermination(Duration.ofSeconds(5));
		long started = System.nanoTime();
		assertThatThrownBy(client::close).isInstanceOfSatisfying(CleanupUnconfirmedException.class,
				ex -> assertThat(ex.cleanup().orElseThrow().inspect().unconfirmedResources())
					.contains("recorder-executor"));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		// The drain is not abandoned: the recording finishes after close returns.
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime() < end)
			Thread.sleep(20);
		assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
		assertThat(recorded).hasSize(1);
	}

}
