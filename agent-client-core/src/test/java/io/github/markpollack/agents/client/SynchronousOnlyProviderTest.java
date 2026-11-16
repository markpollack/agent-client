/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lifecycle provider can declare itself synchronous only: {@code run()} works and
 * {@code runAsync()} fails fast, before any connection opens.
 */
class SynchronousOnlyProviderTest {

	@Test
	void runAsyncIsRefusedAndRunStillWorks() {
		var fake = new AsyncLifecycleTest.Provider();
		var no = new Capability(false, Optional.of("fixture"));
		var provider = synchronousOnly(fake,
				new SessionCapabilities(no, no, no, new Capability(false, Optional.of("sync only"))));
		try (var client = AgentClient.create(provider)) {
			assertThatThrownBy(() -> client.goal("hello").runAsync()).isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("Provider refuses asynchronous executions: sync only; use run()");
			assertThat(fake.calls).hasValue(0);
			assertThat(client.goal("hello").run().getResult()).isEqualTo("hello");
		}
	}

	@Test
	void aRefusalWithoutAReasonStillNamesRun() {
		var no = new Capability(false, Optional.empty());
		var provider = synchronousOnly(new AsyncLifecycleTest.Provider(), new SessionCapabilities(no, no, no, no));
		try (var client = AgentClient.create(provider)) {
			assertThatThrownBy(() -> client.goal("hello").runAsync())
				.hasMessage("Provider refuses asynchronous executions; use run()");
		}
	}

	@Test
	void theThreeCapabilityFormSupportsAsync() {
		var yes = new Capability(true, Optional.empty());
		assertThat(new SessionCapabilities(yes, yes, yes).asynchronous().supported()).isTrue();
		assertThat(new AsyncLifecycleTest.Provider().capabilities().asynchronous().supported()).isTrue();
	}

	private static AgentExecutionProvider synchronousOnly(AgentExecutionProvider delegate,
			SessionCapabilities capabilities) {
		return new AgentExecutionProvider() {
			@Override
			public Connection open(io.github.markpollack.agents.model.AgentOptions options) {
				return delegate.open(options);
			}

			@Override
			public SessionCapabilities capabilities() {
				return capabilities;
			}
		};
	}

}
