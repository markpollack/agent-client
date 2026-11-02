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
class AsyncOwnershipTest {

	@Test
	void synchronousAndAsynchronousFailuresShareDomainTranslation() {
		var provider = new AsyncLifecycleTest.Provider();
		var cause = new IllegalStateException("transport");
		provider.answer = request -> {
			throw cause;
		};
		try (var client = AgentClient.create(provider)) {
			var synchronous = (AgentExecutionException) catchThrowable(() -> client.run("sync"));
			var asynchronous = client.goal("async").runAsync();
			var observed = (AgentExecutionException) catchThrowable(asynchronous::result);
			assertThat(synchronous.getCause()).isSameAs(cause);
			assertThat(observed.getCause()).isSameAs(cause);
			assertThat(synchronous.failure()).isEqualTo(observed.failure());
			assertThat(observed.failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
		}
	}

	@Test
	void synchronousInterruptionOwnsStopAndRestoresFlag() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		provider.answer = request -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
			return AsyncLifecycleTest.response("late");
		};
		var client = AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(30)).build();
		var failure = new AtomicReference<Throwable>();
		var interrupted = new AtomicBoolean();
		Thread caller = Thread.ofPlatform().start(() -> {
			failure.set(catchThrowable(() -> client.run("sync")));
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		try {
			entered.await();
			caller.interrupt();
			caller.join(2000);
			assertThat(caller.isAlive()).isFalse();
			assertThat(failure.get()).isInstanceOf(CancellationException.class);
			assertThat(interrupted).isTrue();
			assertThat(provider.stops).hasSize(1);
		}
		finally {
			release.countDown();
			caller.join();
			try {
				client.close();
			}
			catch (CleanupUnconfirmedException ex) {
				AsyncAcquisitionCloseTest.awaitConfirmed(ex.cleanup().orElseThrow());
				client.close();
			}
		}
	}

	@Test
	void blockedObserverAndRecorderDoNotBlockDefaultRuntimeOrFollowup() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var observers = new CountDownLatch(1);
		var recorders = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var client = AgentClient.builder(provider).executionRecorder(decision -> {
			recorders.countDown();
			AsyncConcurrencyTest.await(release);
			return "test-binding";
		}).build()) {
			var session = client.sessions().create(spec -> {
			});
			var execution = session.goal("first").onEvent(event -> {
				observers.countDown();
				AsyncConcurrencyTest.await(release);
			}).runAsync();
			try {
				assertThat(execution.result(Duration.ofSeconds(2)).getResult()).isEqualTo("first");
				assertThat(observers.await(2, TimeUnit.SECONDS)).isTrue();
				assertThat(recorders.await(2, TimeUnit.SECONDS)).isTrue();
				assertThat(execution.inspect().callbacks().runningSince()).isPresent();
				assertThat(session.goal("next").runAsync().result(Duration.ofSeconds(2)).getResult()).isEqualTo("next");
				assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
			}
			finally {
				release.countDown();
			}
		}
	}

	@Test
	void closeDuringAcquisitionKeepsOwnershipOfLateConnection() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		provider.open = () -> {
			entered.countDown();
			AsyncConcurrencyTest.await(release);
		};
		var client = AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(50)).build();
		var failure = new AtomicReference<Throwable>();
		Thread creator = Thread.ofPlatform()
			.start(() -> failure.set(catchThrowable(() -> client.sessions().create(spec -> {
			}))));
		try {
			entered.await();
			var close = (CleanupUnconfirmedException) catchThrowable(client::close);
			assertThat(close.cleanup().orElseThrow().inspect().unconfirmedResources()).hasSize(1);
			release.countDown();
			creator.join();
			assertThat(((AgentOperationException) failure.get()).failure()).isEqualTo(OperationFailure.CLOSED);
			AsyncAcquisitionCloseTest.awaitConfirmed(close.cleanup().orElseThrow());
			assertThat(provider.closes).hasValue(1);
			client.close();
		}
		finally {
			release.countDown();
			creator.join();
		}
	}

	@Test
	void singleUseExecutionClosesBeforeReturningAndFreezesRequestData() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var data = new ArrayList<>(List.of("before"));
		var options = new DefaultAgentOptions();
		options.setExtras(Map.of("data", data));
		p.answer = request -> {
			assertThat((Object) request.options().getExtras().get("data")).isEqualTo(List.of("before"));
			assertThatThrownBy(() -> request.options().getEnvironmentVariables().put("NEW", "value"))
				.isInstanceOf(UnsupportedOperationException.class);
			return AsyncLifecycleTest.response("ok");
		};
		var execution = m.client(p).goal(new Goal("goal", null, options)).runAsync();
		data.add("later");
		m.run();
		assertThat(execution.result().getResult()).isEqualTo("ok");
		assertThat(p.closes).hasValue(1);
	}

	@Test
	void workerSchedulingRejectionIsAcceptedFailureWithZeroDispatch() throws Exception {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var client = m.client(p);
		var lanes = m.runtime.lanes;
		client.runtime = new ExecutionRuntime(m.now::get, () -> Instant.EPOCH, m.runtime.timer,
				new ExecutionRuntime.Lanes(task -> {
					throw new RejectedExecutionException("full");
				}, lanes.stop(), lanes.publication(), lanes.observer(), lanes.recorder()),
				new ExecutionRuntime.Limits(Duration.ofNanos(20), Duration.ofNanos(10)));
		var execution = client.goal("never").runAsync();
		assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
			.isEqualTo(FailureCode.SCHEDULING);
		assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		assertThat(p.calls).hasValue(0);
	}

	@Test
	void builderDeadlineIsAppliedAndLateObserverFailureIsRecorded() {
		var m = new AsyncLifecycleTest.Manual();
		var p = new AsyncLifecycleTest.Provider();
		var client = (DefaultAgentClient) AgentClient.builder(p).defaultTimeout(Duration.ofNanos(5)).build();
		client.runtime = m.runtime;
		var execution = client.goal("deadline").runAsync();
		m.tick(5);
		m.run();
		assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
			.isEqualTo(FailureCode.TIMEOUT);
		var success = m.client(p).goal("ok").onEvent(event -> {
			throw new IllegalStateException();
		}).runAsync();
		m.run();
		var before = success.inspect();
		m.observer.remove().run();
		assertThat(before.callbacks().failureCount()).isZero();
		assertThat(success.inspect().callbacks().failureCount()).isEqualTo(1);
	}

}
