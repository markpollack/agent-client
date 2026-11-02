/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class AsyncReviewRegressionTest {

	@Test
	void resultBudgetIncludesAdmissionLockContention() throws Exception {
		assertContendedWait(false);
	}

	@Test
	void terminationBudgetIncludesAdmissionLockContention() throws Exception {
		assertContendedWait(true);
	}

	private void assertContendedWait(boolean termination) throws Exception {
		var manual = new AsyncLifecycleTest.Manual();
		var client = manual.client(new AsyncLifecycleTest.Provider());
		var execution = (DefaultAgentExecution) client.goal("pending").runAsync();
		var lock = execution.store.session.lock;
		var failure = new AtomicReference<Throwable>();
		lock.lock();
		Thread waiter = Thread.ofPlatform().unstarted(() -> failure.set(catchThrowable(() -> {
			if (termination)
				execution.awaitTermination(Duration.ofMillis(200));
			else
				execution.result(Duration.ofMillis(200));
		})));
		try {
			waiter.start();
			long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!lock.hasQueuedThread(waiter) && waiter.isAlive() && System.nanoTime() < bound)
				Thread.yield();
			assertThat(lock.hasQueuedThread(waiter)).isTrue();
			waiter.join(1500);
			assertThat(waiter.isAlive()).as("caller budget must expire while lock remains held").isFalse();
			assertThat(failure.get()).isInstanceOf(TimeoutException.class);
			assertThat(execution.isDone()).isFalse();
		}
		finally {
			lock.unlock();
			waiter.join(2000);
			execution.cancel(false);
			manual.run();
			client.close();
		}
	}

	@Test
	void preStartCancellationRetiresResourceFreeOneShot() throws Exception {
		var manual = new AsyncLifecycleTest.Manual();
		var provider = new AsyncLifecycleTest.Provider();
		var client = manual.client(provider);
		var execution = client.goal("cancel").runAsync();
		execution.cancel(false);
		assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		assertThat(owned(client)).isEmpty();
		manual.run();
		assertThat(provider.calls).hasValue(0);
		var next = client.goal("healthy").runAsync();
		manual.run();
		assertThat(next.result().getResult()).isEqualTo("healthy");
	}

	@Test
	void schedulingRejectionRetiresResourceFreeOneShot() throws Exception {
		var manual = new AsyncLifecycleTest.Manual();
		var client = manual.client(new AsyncLifecycleTest.Provider());
		var lanes = manual.runtime.lanes;
		client.runtime = new ExecutionRuntime(manual.now::get, () -> Instant.EPOCH, manual.runtime.timer,
				new ExecutionRuntime.Lanes(task -> {
					throw new RejectedExecutionException("full");
				}, lanes.stop(), lanes.publication(), lanes.observer(), lanes.recorder()),
				new ExecutionRuntime.Limits(Duration.ofNanos(20), Duration.ofNanos(10)));
		var execution = client.goal("rejected").runAsync();
		assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		assertThat(owned(client)).isEmpty();
	}

	@Test
	void failedAcquisitionRetiresResourceFreeOneShot() throws Exception {
		var manual = new AsyncLifecycleTest.Manual();
		var provider = new AsyncLifecycleTest.Provider();
		provider.open = () -> {
			throw new IllegalStateException("setup");
		};
		var client = manual.client(provider);
		var execution = client.goal("failed").runAsync();
		manual.run();
		assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		assertThat(owned(client)).isEmpty();
	}

	@Test
	void unsettledAcquisitionRemainsOwnedThroughUnknownAndLateClose() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		provider.open = () -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
		};
		var client = (DefaultAgentClient) AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(50)).build();
		var execution = client.goal("late").runAsync();
		try {
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			execution.cancel(false);
			assertThat(execution.awaitTermination(Duration.ofSeconds(2)).cleanupConfirmed()).isFalse();
			assertThat(owned(client)).hasSize(1);
			release.countDown();
			long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!owned(client).isEmpty() && System.nanoTime() < bound)
				Thread.yield();
			assertThat(owned(client)).isEmpty();
			assertThat(provider.closes).hasValue(1);
			assertThat(provider.calls).hasValue(0);
			assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isFalse();
		}
		finally {
			release.countDown();
			client.close();
		}
	}

	private static Set<?> owned(DefaultAgentClient client) throws Exception {
		var field = DefaultAgentClient.class.getDeclaredField("ownedSessions");
		field.setAccessible(true);
		return (Set<?>) field.get(client);
	}

}
