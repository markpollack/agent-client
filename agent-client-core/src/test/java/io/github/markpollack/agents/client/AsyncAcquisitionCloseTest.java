/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class AsyncAcquisitionCloseTest {

	@Test
	void timedOutAcquisitionKeepsLateResourceAndCleanupHandle() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		provider.open = () -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
		};
		var client = AgentClient.builder(provider)
			.setupTimeout(Duration.ofMillis(100))
			.cleanupTimeout(Duration.ofMillis(100))
			.build();
		try {
			var failure = (AgentOperationException) catchThrowable(() -> client.sessions().create(spec -> {
			}));
			assertThat(entered.getCount()).isZero();
			assertThat(failure.failure()).isEqualTo(OperationFailure.SETUP_TIMEOUT);
			var cleanup = failure.cleanup().orElseThrow();
			assertThat(cleanup.inspect().unconfirmedResources()).contains("session-acquisition");
			assertThatThrownBy(client::close).isInstanceOf(CleanupUnconfirmedException.class);
			release.countDown();
			awaitConfirmed(cleanup);
			assertThat(provider.closes).hasValue(1);
			assertThat(provider.calls).hasValue(0);
			client.close();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void acquisitionFailureKeepsCauseAndConfirmedCleanup() {
		var provider = new AsyncLifecycleTest.Provider();
		var cause = new IllegalArgumentException("setup");
		provider.open = () -> {
			throw cause;
		};
		try (var client = AgentClient.create(provider)) {
			var failure = (AgentOperationException) catchThrowable(() -> client.sessions().create(spec -> {
			}));
			assertThat(failure.failure()).isEqualTo(OperationFailure.SETUP_FAILED);
			assertThat(failure.getCause()).isSameAs(cause);
			assertThat(failure.cleanup().orElseThrow().inspect().state()).isEqualTo(CleanupState.CONFIRMED);
		}
	}

	@Test
	void interruptedSetupRestoresFlagAndOwnsLateResource() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		provider.open = () -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
		};
		var client = AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(50)).build();
		var failure = new AtomicReference<AgentOperationException>();
		var interrupted = new AtomicBoolean();
		Thread caller = Thread.ofPlatform().start(() -> {
			failure.set((AgentOperationException) catchThrowable(() -> client.sessions().create(spec -> {
			})));
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		try {
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			caller.interrupt();
			caller.join(2000);
			assertThat(caller.isAlive()).isFalse();
			assertThat(interrupted).isTrue();
			assertThat(failure.get().failure()).isEqualTo(OperationFailure.INTERRUPTED);
			release.countDown();
			awaitConfirmed(failure.get().cleanup().orElseThrow());
			client.close();
		}
		finally {
			release.countDown();
			caller.join();
		}
	}

	@Test
	void clientCloseUsesOneOriginalBudgetAndAttemptsEveryConnection() throws Exception {
		var entered = new CountDownLatch(3);
		var release = new CountDownLatch(1);
		AgentExecutionProvider provider = options -> new AgentExecutionProvider.Connection() {
			public AgentExecutionProvider.Turn prepare(String id) {
				throw new UnsupportedOperationException();
			}

			public void close() {
				entered.countDown();
				AsyncConcurrencyTest.await(release);
			}
		};
		var client = AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(150)).build();
		for (int i = 0; i < 3; i++)
			client.sessions().create(spec -> {
			});
		long start = System.nanoTime();
		var failure = (CleanupUnconfirmedException) catchThrowable(client::close);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.cleanup().orElseThrow().inspect().unconfirmedResources()).hasSize(3);
		start = System.nanoTime();
		assertThatThrownBy(client::close).isInstanceOf(CleanupUnconfirmedException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
		release.countDown();
		awaitConfirmed(failure.cleanup().orElseThrow());
		client.close();
		assertThatThrownBy(() -> client.goal("closed").runAsync()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void selfCloseRefusesBeforeMutatingAdmission() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		try (var client = AgentClient.create(provider)) {
			var session = client.sessions().create(spec -> {
			});
			provider.answer = request -> {
				var failure = (AgentOperationException) catchThrowable(session::close);
				assertThat(failure.failure()).isEqualTo(OperationFailure.SELF_CLOSE);
				assertThat(((AgentOperationException) catchThrowable(client::close)).failure())
					.isEqualTo(OperationFailure.SELF_CLOSE);
				return AsyncLifecycleTest.response(request.goal());
			};
			assertThat(session.goal("first").runAsync().result().getResult()).isEqualTo("first");
			assertThat(session.goal("next").runAsync().result().getResult()).isEqualTo("next");
		}
	}

	@Test
	void recorderIsTrackedSeparatelyAndSelfCloseDoesNotMutateClient() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var owner = new AtomicReference<AgentClient>();
		var selfFailure = new AtomicReference<Throwable>();
		var client = AgentClient.builder(provider)
			.cleanupTimeout(Duration.ofMillis(100))
			.executionRecorder(decision -> {
				selfFailure.set(catchThrowable(() -> owner.get().close()));
				entered.countDown();
				AsyncConcurrencyTest.await(release);
				return "ack";
			})
			.build();
		owner.set(client);
		var execution = client.goal("record").runAsync();
		assertThat(execution.result().isSuccessful()).isTrue();
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(((AgentOperationException) selfFailure.get()).failure()).isEqualTo(OperationFailure.SELF_CLOSE);
		var failure = (CleanupUnconfirmedException) catchThrowable(client::close);
		assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		release.countDown();
		awaitConfirmed(failure.cleanup().orElseThrow());
		client.close();
	}

	@Test
	void closeTerminatesOwnedOptionalExecutorsWithoutClosingBorrowedRuntime() throws Exception {
		var client = (DefaultAgentClient) AgentClient.builder(new AsyncLifecycleTest.Provider())
			.executionRecorder(decision -> "ack")
			.build();
		var execution = client.goal("work").runAsync();
		execution.result();
		client.close();
		assertThat(client.runtime.remainingLanes()).isEmpty();
		assertThat(((ExecutorService) client.runtime.lanes.recorder()).isTerminated()).isTrue();
		assertThat(((ExecutorService) client.runtime.lanes.observer()).isTerminated()).isTrue();
		var manual = new AsyncLifecycleTest.Manual();
		var borrowed = manual.client(new AsyncLifecycleTest.Provider());
		borrowed.close();
		assertThat(manual.runtime.remainingLanes()).isEmpty();
	}

	static void awaitConfirmed(CleanupHandle cleanup) {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (cleanup.inspect().state() != CleanupState.CONFIRMED && System.nanoTime() < end)
			Thread.yield();
		assertThat(cleanup.inspect().state()).isEqualTo(CleanupState.CONFIRMED);
	}

}
