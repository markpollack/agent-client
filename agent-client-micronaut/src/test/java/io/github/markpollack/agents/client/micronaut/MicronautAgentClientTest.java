/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.micronaut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import io.github.markpollack.agents.antigravity.AntigravityClientProvider;
import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.codex.CodexClientProvider;
import io.github.markpollack.agents.model.AgentApi;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Micronaut integration builds lifecycle providers from
 * {@code agent-client.lifecycle.*} and an {@link AgentClient.Builder} over them. Runs
 * offline against the Claude provider's fixture CLI.
 */
@Timeout(30)
class MicronautAgentClientTest {

	@TempDir
	Path root;

	@Test
	void propertiesReachTheClaudeProviderThroughTheBuilder() throws Exception {
		Path work = Files.createDirectory(root.resolve("work"));
		try (var context = ApplicationContext
			.run(Map.of("agent-client.lifecycle.claude.enabled", "true", "agent-client.lifecycle.claude.executable",
					fixture().toString(), "agent-client.lifecycle.claude.working-directory", work.toString()))) {
			assertThat(context.getBean(AgentApi.class)).isInstanceOf(ClaudeClientProvider.class);
			try (AgentClient client = context.getBean(AgentClient.Builder.class).build()) {
				assertThat(client.goal("cwd-marker:micronaut").run().isSuccessful()).isTrue();
			}
		}
		assertThat(Files.readString(work.resolve("micronaut"))).isEqualTo(work.toString());
	}

	@Test
	void eachInjectionGetsItsOwnBuilder() {
		try (var context = ApplicationContext.run(Map.of("agent-client.lifecycle.claude.enabled", "true"))) {
			assertThat(context.getBean(AgentClient.Builder.class))
				.isNotSameAs(context.getBean(AgentClient.Builder.class));
		}
	}

	@Test
	void nothingIsCreatedUnlessAProviderIsEnabled() {
		try (var context = ApplicationContext.run(Map.of("agent-client.lifecycle.claude.executable", "claude"))) {
			assertThat(context.findBean(AgentApi.class)).isEmpty();
			assertThat(context.findBean(AgentClient.Builder.class)).isEmpty();
		}
	}

	@Test
	void codexAndAntigravityProvidersAreCreatedWithTheirDefaults() {
		try (var context = ApplicationContext.run(Map.of("agent-client.lifecycle.codex.enabled", "true"))) {
			assertThat(context.getBean(AgentApi.class)).isInstanceOf(CodexClientProvider.class);
		}
		try (var context = ApplicationContext.run(Map.of("agent-client.lifecycle.antigravity.enabled", "true"))) {
			assertThat(context.getBean(AgentApi.class)).isInstanceOf(AntigravityClientProvider.class);
		}
	}

	@ParameterizedTest
	@CsvSource({ "claude, io.github.markpollack.agents.claude.ClaudeClientProvider",
			"codex, io.github.markpollack.agents.codex.CodexClientProvider",
			"antigravity, io.github.markpollack.agents.antigravity.AntigravityClientProvider" })
	void everyPropertyIsAccepted(String provider, Class<?> type) {
		String prefix = "agent-client.lifecycle." + provider;
		Map<String, Object> properties = new HashMap<>(
				Map.of(prefix + ".enabled", "true", prefix + ".working-directory", root.toString(),
						prefix + ".executable", "fixture-cli", prefix + ".acquisition-directory",
						root.resolve("capture").toString(), prefix + ".cli-version", "1.2.3"));
		if (provider.equals("codex")) {
			properties.put(prefix + ".sandbox", "danger-full-access");
		}
		if (provider.equals("antigravity")) {
			properties.put(prefix + ".sandbox", "false");
		}
		try (var context = ApplicationContext.run(properties)) {
			assertThat(context.getBean(AgentApi.class)).isInstanceOf(type);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "claude", "codex", "antigravity" })
	void anAcquisitionDirectoryNeedsTheCliVersion(String provider) {
		String prefix = "agent-client.lifecycle." + provider;
		try (var context = ApplicationContext
			.run(Map.of(prefix + ".enabled", "true", prefix + ".acquisition-directory", root.toString()))) {
			assertThatThrownBy(() -> context.getBean(AgentApi.class)).isInstanceOf(BeanInstantiationException.class)
				.hasStackTraceContaining(prefix + ".acquisition-directory requires " + prefix + ".cli-version");
		}
	}

	private Path fixture() throws Exception {
		Path script = root.resolve("claude-fixture.py");
		Files.copy(Path.of("..", "agent-client-claude", "src", "test", "resources", "retained-child.py"), script);
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		return script;
	}

}
