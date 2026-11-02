/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.agents.model.*;

@Timeout(15)
class AsyncBoundaryTest {

	@Test
	void blockedNormalTeardownUsesOriginalCleanupBoundWithoutExecutionDeadline() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var closing = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		AgentExecutionProvider provider = new AgentExecutionProvider() {
			public Connection open(AgentOptions options) {
				return new Connection() {
					public Turn prepare(String id) {
						return new Turn() {
							public AgentResponse call(AgentTaskRequest request) {
								return AsyncLifecycleTest.response("done");
							}

							public void stop(boolean flag) {
							}
						};
					}

					public void close() {
						closing.countDown();
						AsyncConcurrencyTest.await(release);
					}
				};
			}
		};
		var client = (DefaultAgentClient) AgentClient.create(provider);
		client.runtime = m.runtime;
		var execution = client.goal("x").runAsync();
		Thread worker = Thread.ofPlatform().start(m::run);
		try {
			closing.await();
			assertThat(execution.isDone()).isFalse();
			m.tick(10);
			var frozen = execution.awaitTermination(Duration.ZERO);
			assertThat(frozen.cleanupConfirmed()).isFalse();
			assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
				.isEqualTo(FailureCode.CLIENT_PROCESSING);
			m.stop();
			release.countDown();
			worker.join();
			assertThat(execution.awaitTermination(Duration.ZERO)).isSameAs(frozen);
			assertThat(catchThrowable(execution::result)).isInstanceOf(AgentExecutionException.class);
		}
		finally {
			release.countDown();
			worker.join();
		}
	}

	@Test
	void lateRecordingReceiptPreservesTimeoutHistoryDecisionAndTermination() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var client = m.client(p);
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		client.recorder = decision -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
			return "receiver-binding";
		};
		var execution = client.goal("done").runAsync();
		m.run();
		var decision = execution.decision().orElseThrow();
		var termination = execution.awaitTermination(Duration.ZERO);
		Thread writer = Thread.ofPlatform().start(m.record.remove());
		try {
			entered.await();
			m.tick(Duration.ofSeconds(30).toNanos());
			var expired = execution.recording();
			assertThat(((TerminalDelivery.Failed) expired.delivery()).failure().code())
				.isEqualTo(FailureCode.RECORDING_TIMEOUT);
			assertThat(execution.result().getResult()).isEqualTo("done");
			release.countDown();
			writer.join();
			var completed = (TerminalDelivery.Completed) execution.recording().delivery();
			assertThat(completed.bindingId()).isEqualTo("receiver-binding");
			assertThat(completed.lateAcknowledgment()).isTrue();
			assertThat(expired.delivery()).isInstanceOf(TerminalDelivery.Failed.class);
			assertThat(execution.recording().attempts()).hasSize(2);
			assertThat(execution.decision().orElseThrow()).isSameAs(decision);
			assertThat(execution.awaitTermination(Duration.ZERO)).isSameAs(termination);
			assertThat(p.calls).hasValue(1);
		}
		finally {
			release.countDown();
			writer.join();
		}
	}

	@Test
	void rejectedSecondaryLanesNeverRunOnSubmittingThread() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var client = m.client(p);
		var calls = new AtomicInteger();
		Executor reject = task -> {
			throw new RejectedExecutionException("full");
		};
		var lanes = m.runtime.lanes;
		client.runtime = new ExecutionRuntime(m.now::get, () -> Instant.EPOCH, m.runtime.timer,
				new ExecutionRuntime.Lanes(lanes.worker(), lanes.stop(), lanes.publication(), reject, reject),
				new ExecutionRuntime.Limits(Duration.ofNanos(20), Duration.ofNanos(10)));
		client.recorder = decision -> {
			calls.incrementAndGet();
			return "bad";
		};
		var execution = client.goal("x").onEvent(event -> calls.incrementAndGet()).runAsync();
		m.run();
		assertThat(execution.result().getResult()).isEqualTo("x");
		assertThat(calls).hasValue(0);
		assertThat(execution.inspect().callbacks().droppedCount()).isEqualTo(1);
		assertThat(((TerminalDelivery.Failed) execution.recording().delivery()).acceptanceUnknown()).isFalse();
	}

	@Test
	void alreadyCompleteTerminationDependentsAreStillIsolated() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var execution = m.client(new AsyncLifecycleTest.Provider()).goal("x").runAsync();
		m.run();
		m.publish.remove().run();
		var called = new AtomicBoolean();
		var stage = execution.termination().thenApply(value -> {
			called.set(true);
			return value.cleanupConfirmed();
		});
		assertThat(called).isFalse();
		m.publish.remove().run();
		assertThat(stage.toCompletableFuture().get()).isTrue();
		assertThat(called).isTrue();
		assertThatThrownBy(() -> execution.termination().thenRun(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void mutableCauseTextCannotRewriteFrozenFailureAndCycleTerminates() {
		class MutableFailure extends RuntimeException {

			private static final long serialVersionUID = 1L;

			String detail = "before";

			@Override
			public String getMessage() {
				return this.detail;
			}

			@Override
			public synchronized Throwable getCause() {
				return this;
			}

		}
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var cause = new MutableFailure();
		p.answer = request -> {
			throw cause;
		};
		var execution = m.client(p).goal("x").runAsync();
		m.run();
		m.stop();
		cause.detail = "after";
		var failure = (AgentExecutionException) catchThrowable(execution::result);
		assertThat(failure.failure().message()).isEqualTo("before");
		assertThat(failure.failure().causes()).isEmpty();
		assertThat(failure.getCause().getMessage()).isEqualTo("after");
	}

	@Test
	void deadlineElapsedArithmeticCrossesNanoTimeWrap() {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		m.now.set(Long.MAX_VALUE - 4);
		p.answer = request -> {
			m.now.set(Long.MIN_VALUE + 5);
			return AsyncLifecycleTest.response("late");
		};
		var execution = m.client(p).goal("x").executionTimeout(Duration.ofNanos(10)).runAsync();
		m.run();
		m.stop();
		assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
			.isEqualTo(FailureCode.TIMEOUT);
	}

	@Test
	void firstAcceptedCancellationRevisionContainsItsReason() {
		var m = new AsyncLifecycleTest.Manual();
		var execution = (DefaultAgentExecution) m.client(new AsyncLifecycleTest.Provider()).goal("x").runAsync();
		var reason = new Reason("OWNER", "stop", "test", Instant.EPOCH);
		assertThat(execution.store.cancel(false, reason)).isTrue();
		assertThat(execution.inspect().stopReason()).contains(reason);
		assertThat(execution.inspect().cleanup().reason()).contains(reason);
		execution.driver.advance();
	}

}
