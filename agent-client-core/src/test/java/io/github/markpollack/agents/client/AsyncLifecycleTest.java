/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.agents.model.*;

@Timeout(15)
class AsyncLifecycleTest {

	static final Duration SECOND = Duration.ofSeconds(1);

	static AgentResponse response(String text) {
		return new AgentResponse(List.of(new AgentGeneration(text, new AgentGenerationMetadata("SUCCESS", Map.of()))));
	}

	static final class Provider implements AgentExecutionProvider {

		final List<Turn> turns = new CopyOnWriteArrayList<>();

		final AtomicInteger calls = new AtomicInteger();

		final AtomicInteger closes = new AtomicInteger();

		volatile Function<AgentTaskRequest, AgentResponse> answer = request -> response(request.goal());

		volatile Runnable open = () -> {
		};

		@Override
		public Connection open(AgentOptions options) {
			this.open.run();
			return new Connection() {
				@Override
				public Turn prepare(String id) {
					Turn turn = new Turn() {
						volatile boolean stopped;

						final List<Boolean> flags = new CopyOnWriteArrayList<>();

						@Override
						public AgentResponse call(AgentTaskRequest request) {
							if (this.stopped)
								throw new CancellationException();
							calls.incrementAndGet();
							return answer.apply(request);
						}

						@Override
						public void stop(boolean flag) {
							this.flags.add(flag);
							this.stopped = true;
							stops.add(id + ":" + flag);
						}
					};
					turns.add(turn);
					return turn;
				}

				@Override
				public void close() {
					closes.incrementAndGet();
				}
			};
		}

		final List<String> stops = new CopyOnWriteArrayList<>();

	}

	static final class Manual {

		final AtomicLong now = new AtomicLong();

		final Queue<Runnable> work = new ConcurrentLinkedQueue<>();

		final Queue<Runnable> stops = new ConcurrentLinkedQueue<>();

		final Queue<Runnable> publish = new ConcurrentLinkedQueue<>();

		final Queue<Runnable> observer = new ConcurrentLinkedQueue<>();

		final Queue<Runnable> record = new ConcurrentLinkedQueue<>();

		final List<Scheduled> timers = new CopyOnWriteArrayList<>();

		record Scheduled(long deadline, Runnable task, AtomicBoolean cancelled) {
		}

		final ExecutionRuntime runtime = new ExecutionRuntime(this.now::get,
				() -> Instant.EPOCH.plusNanos(this.now.get()), (task, nanos) -> {
					var scheduled = new Scheduled(this.now.get() + nanos, task, new AtomicBoolean());
					this.timers.add(scheduled);
					return () -> scheduled.cancelled().set(true);
				}, new ExecutionRuntime.Lanes(this.work::add, this.stops::add, this.publish::add, this.observer::add,
						this.record::add),
				new ExecutionRuntime.Limits(Duration.ofNanos(20), Duration.ofNanos(10)));

		void tick(long now) {
			this.now.set(now);
			for (var task : this.timers)
				if (task.deadline() <= now && task.cancelled().compareAndSet(false, true))
					task.task().run();
		}

		void run() {
			try {
				this.work.remove().run();
			}
			finally {
				Thread.interrupted();
			}
		}

		void stop() {
			this.stops.remove().run();
		}

		DefaultAgentClient client(Provider provider) {
			var client = (DefaultAgentClient) AgentClient.create(provider);
			client.runtime = this.runtime;
			client.runtime.acquisition = Runnable::run;
			return client;
		}

	}

	@Test
	void normalCompletionReleasesRetainedSessionAndPreservesOldSnapshots() throws Exception {
		var manual = new Manual();
		var provider = new Provider();
		var client = manual.client(provider);
		var session = client.sessions().create(spec -> {
		});
		var first = session.goal("first").runAsync();
		var before = first.inspect();
		assertThatThrownBy(() -> session.goal("overlap").runAsync()).isInstanceOf(IllegalStateException.class);
		manual.run();
		assertThat(first.result().getResult()).isEqualTo("first");
		assertThat(before.resultState()).isEqualTo(Future.State.RUNNING);
		assertThat(first.inspect().settlement()).isEqualTo(SettlementState.SETTLED);
		var next = session.goal("next").runAsync();
		manual.run();
		assertThat(next.result().getResult()).isEqualTo("next");
		assertThat(first.cancel(true)).isFalse();
		assertThat(provider.stops).isEmpty();
		assertThat(first.executionId()).isNotEqualTo(next.executionId());
		assertThat(first.sessionId()).isEqualTo(next.sessionId());
		assertThat(first.decision()).isPresent();
	}

	@Test
	void callerTimeoutDoesNotStopAndZeroPollIsReadyFirst() throws Exception {
		var m = new Manual();
		var p = new Provider();
		var execution = m.client(p).goal("ok").runAsync();
		assertThatThrownBy(() -> execution.result(Duration.ZERO)).isInstanceOf(TimeoutException.class);
		assertThat(execution.isDone()).isFalse();
		assertThat(p.stops).isEmpty();
		m.run();
		assertThat(execution.result(Duration.ZERO).getResult()).isEqualTo("ok");
	}

	@Test
	void beforeEqualAfterDeadlinesAndLateTimer() throws Exception {
		for (long completion : new long[] { 9, 10, 11 }) {
			var m = new Manual();
			var p = new Provider();
			p.answer = request -> {
				m.now.set(completion);
				return response("result");
			};
			var execution = m.client(p).goal("goal").executionTimeout(Duration.ofNanos(10)).runAsync();
			m.run();
			if (completion == 9)
				assertThat(execution.result().getResult()).isEqualTo("result");
			else {
				assertThat(catchThrowable(execution::result)).isInstanceOf(AgentExecutionException.class);
				assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
					.isEqualTo(FailureCode.TIMEOUT);
				assertThat(execution.isCancelled()).isFalse();
				m.stop();
			}
			assertThat(execution.awaitTermination(Duration.ZERO).cleanupConfirmed()).isTrue();
		}
	}

	@Test
	void setupDeadlineOrderAndExecutionTie() {
		for (long executionBudget : new long[] { 20, 25 }) {
			var m = new Manual();
			var p = new Provider();
			p.open = () -> m.now.set(30);
			var execution = m.client(p).goal("goal").executionTimeout(Duration.ofNanos(executionBudget)).runAsync();
			m.run();
			var failure = ((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure();
			assertThat(failure.code())
				.isEqualTo(executionBudget == 20 ? FailureCode.TIMEOUT : FailureCode.SETUP_TIMEOUT);
			assertThat(p.calls).hasValue(0);
			m.stop();
		}
	}

	@Test
	void noConfiguredDeadlineAndLargeDuration() throws Exception {
		var m = new Manual();
		var p = new Provider();
		p.answer = request -> {
			m.now.set(Long.MAX_VALUE / 2);
			return response("ok");
		};
		var client = m.client(p);
		var execution = client.goal("goal").runAsync();
		m.run();
		assertThat(execution.result(Duration.ofSeconds(Long.MAX_VALUE)).getResult()).isEqualTo("ok");
		assertThatThrownBy(() -> client.goal("x").executionTimeout(Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> execution.result(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> execution.result(null)).isInstanceOf(NullPointerException.class);
		assertThat(ExecutionTimes.nanos(Duration.ofSeconds(Long.MAX_VALUE), true)).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void preDispatchCancelBothFlagsDispatchNothingAndAreFresh() {
		for (boolean flag : new boolean[] { false, true }) {
			var m = new Manual();
			var p = new Provider();
			var execution = m.client(p).goal("x").runAsync();
			assertThat(execution.cancel(flag)).isTrue();
			m.run();
			assertThat(p.calls).hasValue(0);
			assertThat(p.turns).isEmpty();
			assertThat(execution.isCancelled()).isTrue();
			Throwable first = catchThrowable(execution::result), second = catchThrowable(execution::result);
			assertThat(first).isInstanceOf(CancellationException.class).isNotSameAs(second);
		}
	}

	@Test
	void failureDescriptorsAndResponseCopiesAreDetached() throws Exception {
		var m = new Manual();
		var p = new Provider();
		var original = new IllegalStateException("original");
		p.answer = request -> {
			throw original;
		};
		var execution = m.client(p).goal("x").runAsync();
		m.run();
		m.stop();
		var first = (AgentExecutionException) catchThrowable(execution::result);
		original.setStackTrace(new StackTraceElement[0]);
		original.addSuppressed(new RuntimeException("later"));
		var second = (AgentExecutionException) catchThrowable(execution::result);
		assertThat(first).isNotSameAs(second);
		assertThat(first.getCause()).isSameAs(original);
		assertThat(first.failure()).isEqualTo(second.failure());
		assertThat(second.failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
		assertThat(first.getStackTrace())
			.anyMatch(frame -> frame.getMethodName().equals("failureDescriptorsAndResponseCopiesAreDetached"));
		var nested = new ArrayList<>(List.of("before"));
		var supplied = new AgentResponse(
				List.of(new AgentGeneration("ok", new AgentGenerationMetadata("SUCCESS", Map.of()))),
				new AgentResponseMetadata("", Duration.ZERO, "", Map.of("nested", nested)));
		p.answer = request -> supplied;
		var success = m.client(p).goal("y").runAsync();
		m.run();
		nested.add("after");
		var returned = success.result();
		returned.getMetadata().put("caller", true);
		assertThat((Object) success.result().getMetadata().get("nested")).isEqualTo(List.of("before"));
		assertThat(success.responseSnapshot().orElseThrow().getMetadata()).doesNotContainKey("caller");
	}

	@Test
	void requiredAdvisorFailureDoesNotReplayEffects() {
		var m = new Manual();
		var p = new Provider();
		var advisor = new io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor() {
			public AgentClientResponse adviseCall(AgentClientRequest request,
					io.github.markpollack.agents.client.advisor.api.AgentCallAdvisorChain chain) {
				chain.nextCall(request);
				throw new IllegalArgumentException("postprocessing");
			}

			public String getName() {
				return "failure";
			}

			public int getOrder() {
				return 0;
			}
		};
		var execution = m.client(p).goal("effect").advisors(advisor).runAsync();
		m.run();
		assertThat(p.calls).hasValue(1);
		assertThat(((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure().code())
			.isEqualTo(FailureCode.ADVISOR);
		m.stop();
		assertThat(p.calls).hasValue(1);
	}

	@Test
	void observerAndRecordingAreSeparateFromOutcomeAndAdmission() throws Exception {
		var m = new Manual();
		var p = new Provider();
		var client = m.client(p);
		client.recorder = decision -> {
			throw new IllegalStateException("storage");
		};
		var session = client.sessions().create(spec -> {
		});
		var execution = session.goal("ok").onEvent(event -> {
			throw new IllegalStateException("observer");
		}).runAsync();
		m.run();
		assertThat(execution.result().getResult()).isEqualTo("ok");
		var pending = execution.recording();
		assertThat(pending.delivery()).isInstanceOf(TerminalDelivery.Pending.class);
		assertThat(session.inspect().state()).isEqualTo(SessionState.READY);
		m.observer.remove().run();
		m.record.remove().run();
		assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Failed.class);
		assertThat(pending.delivery()).isInstanceOf(TerminalDelivery.Pending.class);
		assertThat(execution.result().getResult()).isEqualTo("ok");
	}

	@Test
	void stopResponseIsOrdinaryAndUnknownSnapshotValueFailsCopy() throws Exception {
		var m = new Manual();
		var p = new Provider();
		p.answer = request -> new AgentResponse(
				List.of(new AgentGeneration("refused", new AgentGenerationMetadata("STOP", Map.of()))));
		var execution = m.client(p).goal("x").runAsync();
		m.run();
		assertThat(execution.result().isSuccessful()).isFalse();
		assertThat(execution.inspect().resultState()).isEqualTo(Future.State.SUCCESS);
		p.answer = request -> new AgentResponse(List.of(new AgentGeneration("x")),
				new AgentResponseMetadata("", Duration.ZERO, "", Map.of("opaque", new Object())));
		var failed = m.client(p).goal("x").runAsync();
		m.run();
		assertThat(((ExecutionOutcome.Failed) failed.decision().orElseThrow().outcome()).failure().code())
			.isEqualTo(FailureCode.RESULT_COPY);
		m.stop();
	}

	@Test
	void stableWireNamesAndUnknownRejection() {
		for (var code : FailureCode.values())
			assertThat(FailureCode.fromWireName(code.wireName())).isEqualTo(code);
		assertThatThrownBy(() -> FailureCode.fromWireName("provider-custom"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FailureInfo(null, "", "", List.of())).isInstanceOf(NullPointerException.class);
	}

	@Test
	void nativeProviderDoesNotAccidentallyAcquireAsyncCapability() {
		var client = AgentClient.create(request -> response("legacy"));
		assertThat(client.run("x").getResult()).isEqualTo("legacy");
		assertThatThrownBy(() -> client.goal("x").runAsync()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(client::sessions).isInstanceOf(UnsupportedOperationException.class);
	}

}
