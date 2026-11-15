/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus;

import java.nio.file.Path;
import java.util.Optional;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The producers on their own: properties map onto each provider's builder, an acquisition
 * directory needs the CLI version, and the builder producer needs exactly one agent. The
 * deployment module's tests run them inside Quarkus.
 */
class LifecycleProducersTest {

	@TempDir
	Path root;

	private final Optional<String> none = Optional.empty();

	@Test
	void everyPropertyIsAccepted() {
		Optional<String> dir = Optional.of(root.toString());
		Optional<String> capture = Optional.of(root.resolve("capture").toString());
		Optional<String> version = Optional.of("1.2.3");
		assertThat(new ClaudeLifecycleProducer().claudeClientProvider(dir, Optional.of("claude"), capture, version))
			.isNotNull();
		assertThat(new CodexLifecycleProducer().codexClientProvider(dir, Optional.of("codex"), capture, version,
				Optional.of("workspace-write")))
			.isNotNull();
		assertThat(new AntigravityLifecycleProducer().antigravityClientProvider(dir, Optional.of("agy"), capture,
				version, Optional.of(true)))
			.isNotNull();
	}

	@Test
	void unsetPropertiesKeepTheDefaults() {
		assertThat(new ClaudeLifecycleProducer().claudeClientProvider(none, none, none, none)).isNotNull();
		assertThat(new CodexLifecycleProducer().codexClientProvider(none, none, none, none, none)).isNotNull();
		assertThat(
				new AntigravityLifecycleProducer().antigravityClientProvider(none, none, none, none, Optional.empty()))
			.isNotNull();
	}

	@Test
	void anAcquisitionDirectoryNeedsTheCliVersion() {
		Optional<String> capture = Optional.of(root.toString());
		assertThatThrownBy(() -> new ClaudeLifecycleProducer().claudeClientProvider(none, none, capture, none))
			.hasMessage(
					"agent-client.lifecycle.claude.acquisition-directory requires agent-client.lifecycle.claude.cli-version");
		assertThatThrownBy(() -> new CodexLifecycleProducer().codexClientProvider(none, none, capture, none, none))
			.hasMessage(
					"agent-client.lifecycle.codex.acquisition-directory requires agent-client.lifecycle.codex.cli-version");
		assertThatThrownBy(() -> new AntigravityLifecycleProducer()
			.antigravityClientProvider(none, none, capture, none, Optional.empty())).hasMessage(
					"agent-client.lifecycle.antigravity.acquisition-directory requires agent-client.lifecycle.antigravity.cli-version");
	}

	@Test
	@SuppressWarnings("unchecked")
	void theBuilderNeedsExactlyOneAgent() {
		var producer = new AgentClientBuilderProducer();
		Instance<AgentApi> one = mock(Instance.class);
		when(one.isResolvable()).thenReturn(true);
		when(one.get()).thenReturn(new ClaudeLifecycleProducer().claudeClientProvider(none, none, none, none));
		assertThat(producer.agentClientBuilder(one)).isInstanceOf(AgentClient.Builder.class);

		Instance<AgentApi> missing = mock(Instance.class);
		when(missing.isUnsatisfied()).thenReturn(true);
		assertThatThrownBy(() -> producer.agentClientBuilder(missing)).hasMessageStartingWith("No AgentApi bean");

		Instance<AgentApi> several = mock(Instance.class);
		assertThatThrownBy(() -> producer.agentClientBuilder(several))
			.hasMessageStartingWith("More than one AgentApi bean");
	}

}
