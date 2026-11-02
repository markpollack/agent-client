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

@Timeout(20)
class AsyncConcurrencyTest {

	static void await(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			}
			catch (InterruptedException ex) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	@Test
	void readySuccessFailureAndCancellationPreserveInterruptOnBothThreadKinds() throws Exception {
		for (boolean virtual : new boolean[] { false, true }) {
			for (int outcome = 0; outcome < 3; outcome++) {
				var m = new AsyncLifecycleTest.Manual();
				var p = new AsyncLifecycleTest.Provider();
				if (outcome == 1)
					p.answer = request -> {
						throw new IllegalStateException("failure");
					};
				var execution = m.client(p).goal("ready").runAsync();
				if (outcome == 2)
					execution.cancel(false);
				m.run();
				if (outcome == 1)
					m.stop();
				var failure = new AtomicReference<Throwable>();
				int expected = outcome;
				Runnable body = () -> {
					try {
						Thread.currentThread().interrupt();
						Throwable thrown = catchThrowable(() -> execution.result(Duration.ZERO));
						if (expected == 0)
							assertThat(thrown).isNull();
						if (expected == 1)
							assertThat(thrown).isInstanceOf(AgentExecutionException.class);
						if (expected == 2)
							assertThat(thrown).isInstanceOf(CancellationException.class);
						assertThat(Thread.currentThread().isInterrupted()).isTrue();
						assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
						assertThat(Thread.currentThread().isInterrupted()).isTrue();
					}
					catch (Throwable ex) {
						failure.set(ex);
					}
					finally {
						Thread.interrupted();
					}
				};
				Thread thread = virtual ? Thread.ofVirtual().start(body) : Thread.ofPlatform().start(body);
				thread.join();
				assertThat(failure.get()).isNull();
			}
		}
	}

	@Test
	void interruptOnePendingWaiterLeavesAnotherAndExecutionAlive() throws Exception {
		for (boolean virtual : new boolean[] { false, true }) {
			var m = new AsyncLifecycleTest.Manual();
			var p = new AsyncLifecycleTest.Provider();
			var execution = m.client(p).goal("done").runAsync();
			var entered = new CountDownLatch(2);
			var first = new AtomicReference<Throwable>();
			var second = new AtomicReference<String>();
			Runnable waitOne = () -> {
				entered.countDown();
				first.set(catchThrowable(execution::result));
			};
			Runnable waitTwo = () -> {
				entered.countDown();
				try {
					second.set(execution.result().getResult());
				}
				catch (InterruptedException ex) {
					throw new AssertionError(ex);
				}
			};
			Thread one = virtual ? Thread.ofVirtual().start(waitOne) : Thread.ofPlatform().start(waitOne);
			Thread two = virtual ? Thread.ofVirtual().start(waitTwo) : Thread.ofPlatform().start(waitTwo);
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			one.interrupt();
			one.join();
			assertThat(first.get()).isInstanceOf(InterruptedException.class);
			assertThat(execution.isDone()).isFalse();
			assertThat(p.stops).isEmpty();
			m.run();
			two.join();
			assertThat(second.get()).isEqualTo("done");
		}
	}

	@Test
	void completionInterruptionRaceHasOnlyAllowedOutcomes() throws Exception {
		for (boolean virtual : new boolean[] { false, true }) {
			for (int i = 0; i < 20; i++) {
				var m = new AsyncLifecycleTest.Manual();
				var execution = m.client(new AsyncLifecycleTest.Provider()).goal("value").runAsync();
				var entered = new CountDownLatch(1);
				var result = new AtomicReference<Object>();
				Runnable body = () -> {
					entered.countDown();
					try {
						result.set(execution.result().getResult());
					}
					catch (InterruptedException ex) {
						result.set(ex);
					}
				};
				Thread waiter = virtual ? Thread.ofVirtual().start(body) : Thread.ofPlatform().start(body);
				entered.await();
				waiter.interrupt();
				m.run();
				waiter.join();
				assertThat(result.get().equals("value") || result.get() instanceof InterruptedException).isTrue();
				assertThat(execution.result().getResult()).isEqualTo("value");
			}
		}
	}

	@Test
	void cancellationTargetsOnlyCurrentTurnAndUnknownCannotBeReopened() throws Exception {
		for (boolean flag : new boolean[] { false, true }) {
			var m = new AsyncLifecycleTest.Manual();
			var p = new AsyncLifecycleTest.Provider();
			var client = m.client(p);
			var entered = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			p.answer = request -> {
				entered.countDown();
				await(release);
				return AsyncLifecycleTest.response("late");
			};
			var session = client.sessions().create(spec -> {
			});
			var execution = session.goal("x").runAsync();
			Thread worker = Thread.ofPlatform().start(m::run);
			try {
				entered.await();
				assertThat(execution.cancel(flag)).isTrue();
				m.stop();
				assertThat(p.stops).containsExactly(execution.executionId() + ":" + flag);
				assertThat(execution.inspect().settlement()).isEqualTo(SettlementState.PENDING);
				assertThatThrownBy(() -> session.goal("overlap").runAsync()).isInstanceOf(IllegalStateException.class);
				m.tick(5);
				assertThat(execution.cancel(!flag)).isFalse();
				session.close();
				assertThatThrownBy(() -> execution.awaitTermination(Duration.ZERO))
					.isInstanceOf(TimeoutException.class);
				m.tick(10);
				var frozen = execution.awaitTermination(Duration.ZERO);
				assertThat(frozen.cleanupConfirmed()).isFalse();
				assertThat(frozen.sessionState()).isEqualTo(SessionState.UNKNOWN);
				release.countDown();
				worker.join();
				assertThat(execution.awaitTermination(Duration.ZERO)).isSameAs(frozen);
				assertThat(execution.isCancelled()).isTrue();
				assertThat(p.calls).hasValue(1);
				assertThatThrownBy(() -> session.goal("later").runAsync()).isInstanceOf(IllegalStateException.class);
			}
			finally {
				release.countDown();
				worker.join();
			}
		}
	}

	@Test
	void failureDoesNotReleaseWhileRequiredAdvisorIsStillRunning() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var client = m.client(p);
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var advisor = new io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor() {
			public AgentClientResponse adviseCall(AgentClientRequest request,
					io.github.markpollack.agents.client.advisor.api.AgentCallAdvisorChain chain) {
				var response = chain.nextCall(request);
				entered.countDown();
				await(release);
				return response;
			}

			public String getName() {
				return "required";
			}

			public int getOrder() {
				return 0;
			}
		};
		var session = client.sessions().create(spec -> {
		});
		var execution = session.goal("x").advisors(advisor).executionTimeout(Duration.ofNanos(5)).runAsync();
		Thread worker = Thread.ofPlatform().start(m::run);
		try {
			entered.await();
			m.tick(5);
			m.stop();
			assertThat(execution.isDone()).isTrue();
			assertThat(execution.isCancelled()).isFalse();
			assertThat(execution.inspect().settlement()).isEqualTo(SettlementState.PENDING);
			assertThatThrownBy(() -> session.goal("next").runAsync()).isInstanceOf(IllegalStateException.class);
			release.countDown();
			worker.join();
			assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
			var next = session.goal("next").runAsync();
			m.run();
			assertThat(next.result().getResult()).isEqualTo("next");
			assertThat(execution.cancel(true)).isFalse();
			assertThat(p.stops).hasSize(1);
		}
		finally {
			release.countDown();
			worker.join();
		}
	}

	@Test
	void isolatedStageCannotBeMutatedAndBlockedDependentCannotBlockDirectTermination() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var execution = m.client(new AsyncLifecycleTest.Provider()).goal("x").runAsync();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		execution.termination().thenRun(() -> {
			entered.countDown();
			await(release);
		});
		var escaped = execution.termination().toCompletableFuture();
		escaped.cancel(true);
		m.run();
		Thread publisher = Thread.ofPlatform().start(m.publish.remove());
		try {
			entered.await();
			assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
			assertThat(execution.result().getResult()).isEqualTo("x");
			assertThat(execution.termination().toCompletableFuture().get(1, TimeUnit.SECONDS).cleanupConfirmed())
				.isTrue();
		}
		finally {
			release.countDown();
			publisher.join();
		}
	}

	@Test
	void readerStartedBeforeProducerObservesDetachedDataThroughResultOnly() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var execution = m.client(p).goal("published").runAsync();
		var started = new CountDownLatch(1);
		var observed = new AtomicReference<String>();
		Thread reader = Thread.ofVirtual().start(() -> {
			started.countDown();
			try {
				observed.set(execution.result().getResult());
			}
			catch (InterruptedException ex) {
				throw new AssertionError(ex);
			}
		});
		started.await();
		Thread writer = Thread.ofPlatform().start(m::run);
		reader.join();
		assertThat(observed.get()).isEqualTo("published");
		writer.join();
	}

}
