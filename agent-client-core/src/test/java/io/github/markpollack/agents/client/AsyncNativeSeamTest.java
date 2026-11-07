/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

@Timeout(15)
class AsyncNativeSeamTest {

	static final class Provider implements AgentExecutionProvider {

		AgentOptions opened;

		AgentOptions refreshed;

		final AtomicInteger calls = new AtomicInteger();

		final AtomicInteger closes = new AtomicInteger();

		int generation;

		@Override
		public Connection open(AgentOptions options) {
			this.opened = options;
			return new Connection() {
				@Override
				public Optional<ProviderReference> providerReference() {
					return Optional.of(new ProviderReference("test", "scope", "history"));
				}

				@Override
				public Optional<String> connectionId() {
					return Optional.of("connection-" + generation);
				}

				@Override
				public void refresh(AgentOptions options) {
					refreshed = options;
					generation++;
				}

				@Override
				public Turn prepare(String id) {
					return prepare(id, event -> {
					});
				}

				@Override
				public Turn prepare(String id, Consumer<Map<String, Object>> progress) {
					return new Turn() {
						@Override
						public AgentResponse call(AgentTaskRequest request) {
							calls.incrementAndGet();
							for (int i = 0; i < 200; i++)
								progress.accept(Map.of("index", i));
							return AsyncLifecycleTest.response(request.goal());
						}

						@Override
						public void stop(boolean flag) {
						}
					};
				}

				@Override
				public void close() {
					closes.incrementAndGet();
				}
			};
		}

	}

	@Test
	void immutableRefreshPreservesScopeAndUpdatesDecisionGeneration() throws Exception {
		var provider = new Provider();
		var environment = new HashMap<>(Map.of("TOKEN_FILE", "original"));
		var original = new ConnectionBinding("slot", new McpServerDefinition.HttpDefinition("https://old.test"),
				environment);
		environment.put("TOKEN_FILE", "mutated");
		var fresh = new ConnectionBinding("slot", new McpServerDefinition.HttpDefinition("https://new.test"),
				original.environment());
		try (var client = AgentClient.create(provider);
				var session = client.sessions().create(s -> s.binding(original))) {
			assertThat(provider.opened.getEnvironmentVariables()).containsEntry("TOKEN_FILE", "original");
			var first = session.goal("first").runAsync();
			first.result();
			assertThat(first.decision().orElseThrow().connectionId()).contains("connection-0");
			var next = session.goal("new context").refreshBinding(fresh).runAsync();
			next.result();
			assertThat(next.decision().orElseThrow().connectionId()).contains("connection-1");
			assertThat(provider.refreshed.getMcpServerDefinitions()).containsEntry("slot",
					fresh.mcp().orElseThrow().definition());
			assertThat(session.providerReference()).contains(new ProviderReference("test", "scope", "history"));
			assertThat(session.inspect().providerReference()).isEqualTo(session.providerReference());
			assertThat(first.decision().orElseThrow().connectionId()).contains("connection-0");
			assertThatThrownBy(
					() -> session.goal("bad").refreshBinding(ConnectionBinding.withoutMcp(Map.of())).runAsync())
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(provider.calls).hasValue(2);
			assertThat(session.inspect().state()).isEqualTo(SessionState.READY);
		}
	}

	@Test
	void duplicateInitialSlotAndRefreshWithoutAuthorityRefuseBeforeDispatch() {
		var provider = new Provider();
		var slot = new McpServerDefinition.HttpDefinition("https://host.test");
		try (var client = AgentClient.create(provider)) {
			assertThatThrownBy(() -> client.sessions()
				.create(s -> s.options(DefaultAgentOptions.builder().mcpServerDefinitions(Map.of("slot", slot)).build())
					.binding(new ConnectionBinding("slot", slot, Map.of()))))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(provider.opened).isNull();
			try (var session = client.sessions().create(s -> {
			})) {
				assertThatThrownBy(
						() -> session.goal("bad").refreshBinding(ConnectionBinding.withoutMcp(Map.of())).runAsync())
					.isInstanceOf(IllegalArgumentException.class);
				assertThat(provider.calls).hasValue(0);
			}
		}
	}

	@Test
	void progressIsBoundedOrderedAndDoesNotHoldHealthyReuse() throws Exception {
		var provider = new Provider();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var events = new CopyOnWriteArrayList<AgentClientEvent>();
		try (var client = AgentClient.create(provider); var session = client.sessions().create(s -> {
		})) {
			try {
				var execution = session.goal("first").onEvent(event -> {
					events.add(event);
					entered.countDown();
					try {
						release.await();
					}
					catch (InterruptedException ex) {
						throw new AssertionError(ex);
					}
				}).runAsync();
				execution.result();
				assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
				assertThat(execution.inspect().callbacks().droppedCount()).isGreaterThan(0);
				assertThat(execution.inspect().callbacks().pendingCount()).isLessThanOrEqualTo(64);
				assertThat(session.goal("second").runAsync().result().getResult()).isEqualTo("second");
				release.countDown();
				long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (events.stream().noneMatch(e -> e.kind() == EventKind.OUTCOME) && System.nanoTime() < end)
					Thread.sleep(1);
				assertThat(events.getLast().kind()).isEqualTo(EventKind.OUTCOME);
				assertThat(events.stream().map(AgentClientEvent::sequence).toList()).isSorted();
			}
			finally {
				release.countDown();
			}
		}
	}

	@Test
	void partialAcquisitionIsTransferredAndClosedWithOriginalCause() throws Exception {
		var closes = new AtomicInteger();
		var cause = new IllegalStateException("launch failed");
		AgentExecutionProvider provider = options -> {
			var partial = new AgentExecutionProvider.Connection() {
				@Override
				public AgentExecutionProvider.Turn prepare(String id) {
					throw new AssertionError();
				}

				@Override
				public void close() {
					closes.incrementAndGet();
				}
			};
			throw new ConnectionAcquisitionException("partial setup", cause, partial);
		};
		try (var client = AgentClient.create(provider)) {
			assertThatThrownBy(() -> client.sessions().create(s -> {
			})).isInstanceOf(AgentOperationException.class).hasRootCause(cause);
			assertThat(closes).hasValue(1);
			var execution = client.goal("one shot").runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class).hasRootCause(cause);
			// Closing the partial connection is cleanup: it is confirmed by termination,
			// which can follow the failed result.
			assertThat(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			assertThat(closes).hasValue(2);
		}
	}

}
