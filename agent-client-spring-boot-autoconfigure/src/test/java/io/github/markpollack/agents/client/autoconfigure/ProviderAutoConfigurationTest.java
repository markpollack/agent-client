/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.github.markpollack.agents.amazonq.AmazonQAgentModel;
import io.github.markpollack.agents.amazonq.autoconfigure.AmazonQAgentAutoConfiguration;
import io.github.markpollack.agents.amazonqsdk.AmazonQClient;
import io.github.markpollack.agents.amp.AmpAgentModel;
import io.github.markpollack.agents.amp.autoconfigure.AmpAgentAutoConfiguration;
import io.github.markpollack.agents.antigravity.AntigravityAgentModel;
import io.github.markpollack.agents.antigravity.autoconfigure.AntigravityAgentAutoConfiguration;
import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.autoconfigure.ClaudeAgentAutoConfiguration;
import io.github.markpollack.agents.codex.CodexAgentModel;
import io.github.markpollack.agents.codex.autoconfigure.CodexAgentAutoConfiguration;
import io.github.markpollack.agents.gemini.GeminiAgentModel;
import io.github.markpollack.agents.gemini.autoconfigure.GeminiAgentAutoConfiguration;
import io.github.markpollack.agents.grok.GrokAgentModel;
import io.github.markpollack.agents.grok.autoconfigure.GrokAgentAutoConfiguration;
import io.github.markpollack.agents.junie.JunieAgentModel;
import io.github.markpollack.agents.junie.autoconfigure.JunieAgentAutoConfiguration;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.qwencode.QwenCodeAgentModel;
import io.github.markpollack.agents.qwencode.autoconfigure.QwenCodeAgentAutoConfiguration;
import io.github.markpollack.sandbox.Sandbox;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Each legacy provider auto-configuration, started for real: with every property bound it
 * creates its agent, it backs off when the application defines that bean, and it is
 * absent without its provider on the classpath. Offline: no CLI is run.
 */
@SuppressWarnings("removal")
class ProviderAutoConfigurationTest {

	@TempDir
	static Path tmp;

	/**
	 * A provider: its auto-configuration, its agent type, and every property it binds.
	 */
	record Provider(String name, Class<?> configuration, Class<?> marker, Class<?> agentType,
			Supplier<List<String>> properties) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	static Stream<Arguments> providers() {
		return Stream.of(
				new Provider("amazon-q", AmazonQAgentAutoConfiguration.class, AmazonQClient.class,
						AmazonQAgentModel.class,
						() -> List.of("agent-client.amazon-q.model=m", "agent-client.amazon-q.timeout=30s",
								"agent-client.amazon-q.trust-all-tools=false", "agent-client.amazon-q.trust-tools=a,b",
								"agent-client.amazon-q.agent=dev", "agent-client.amazon-q.verbose=true",
								"agent-client.amazon-q.executable-path=" + cli("q"))),
				new Provider("amp", AmpAgentAutoConfiguration.class, AmpAgentModel.class, AgentModel.class,
						() -> List.of("agent-client.amp.model=m", "agent-client.amp.timeout=30s",
								"agent-client.amp.dangerously-allow-all=false",
								"agent-client.amp.executable-path=" + cli("amp"))),
				new Provider("antigravity", AntigravityAgentAutoConfiguration.class, AntigravityAgentModel.class,
						AgentModel.class,
						() -> List.of("agent-client.antigravity.mode=LOOSE", "agent-client.antigravity.model=m",
								"agent-client.antigravity.effort=high", "agent-client.antigravity.timeout=30s",
								"agent-client.antigravity.dangerously-skip-permissions=true",
								"agent-client.antigravity.execution-mode=ACCEPT_EDITS",
								"agent-client.antigravity.sandbox=true",
								"agent-client.antigravity.executable-path=" + cli("agy"))),
				new Provider("claude", ClaudeAgentAutoConfiguration.class, ClaudeAgentModel.class,
						ClaudeAgentModel.class,
						() -> List.of("agent-client.claude.model=m", "agent-client.claude.timeout=30s",
								"agent-client.claude.yolo=false",
								"agent-client.claude.executable-path=" + cli("claude"),
								"agent-client.claude.effort=high", "agent-client.claude.max-thinking-tokens=100",
								"agent-client.claude.system-prompt=be brief", "agent-client.claude.allowed-tools=Read",
								"agent-client.claude.disallowed-tools=Bash",
								"agent-client.claude.permission-mode=acceptEdits",
								"agent-client.claude.json-schema.type=object", "agent-client.claude.max-tokens=200",
								"agent-client.claude.max-turns=3", "agent-client.claude.max-budget-usd=1.5",
								"agent-client.claude.fallback-model=f", "agent-client.claude.append-system-prompt=more",
								"agent-client.claude.add-dirs=/tmp", "agent-client.claude.settings=s.json",
								"agent-client.claude.permission-prompt-tool-name=ask",
								"agent-client.claude.extra-args.debug=true", "agent-client.claude.env.K=V",
								"agent-client.claude.max-buffer-size=1024", "agent-client.claude.user=u",
								"agent-client.claude.mcp.servers.files.type=stdio",
								"agent-client.claude.mcp.servers.files.command=npx",
								"agent-client.claude.mcp.servers.files.args=-y,server",
								"agent-client.claude.mcp.servers.files.env.TOKEN=t",
								"agent-client.claude.mcp.servers.web.type=http",
								"agent-client.claude.mcp.servers.web.url=http://localhost:1",
								"agent-client.claude.mcp.servers.web.headers.Authorization=x")),
				new Provider("codex", CodexAgentAutoConfiguration.class, CodexAgentModel.class, AgentModel.class,
						() -> List.of("agent-client.codex.mode=LOOSE", "agent-client.codex.model=m",
								"agent-client.codex.reasoning-effort=high", "agent-client.codex.timeout=30s",
								"agent-client.codex.full-auto=false", "agent-client.codex.skip-git-check=true",
								"agent-client.codex.dangerously-bypass-sandbox=false",
								"agent-client.codex.additional-directories=/tmp",
								"agent-client.codex.executable-path=" + cli("codex"))),
				new Provider("gemini", GeminiAgentAutoConfiguration.class, GeminiAgentModel.class,
						GeminiAgentModel.class,
						() -> List.of("agent-client.gemini.model=gemini-test", "agent-client.gemini.timeout=30s",
								"agent-client.gemini.yolo=false",
								"agent-client.gemini.executable-path=" + cli("gemini"),
								"agent-client.gemini.temperature=0.2", "agent-client.gemini.max-tokens=100")),
				new Provider("grok", GrokAgentAutoConfiguration.class, GrokAgentModel.class, AgentModel.class,
						() -> List.of("agent-client.grok.mode=LOOSE", "agent-client.grok.model=m",
								"agent-client.grok.reasoning-effort=high", "agent-client.grok.timeout=30s",
								"agent-client.grok.permission-mode=BYPASS_PERMISSIONS", "agent-client.grok.max-turns=3",
								"agent-client.grok.disable-web-search=true",
								"agent-client.grok.executable-path=" + cli("grok"))),
				new Provider("junie", JunieAgentAutoConfiguration.class, JunieAgentModel.class, AgentModel.class,
						() -> List.of("agent-client.junie.executable-path=" + cli("junie"),
								"agent-client.junie.model=m", "agent-client.junie.effort=high",
								"agent-client.junie.timeout=30s", "agent-client.junie.sessions-directory=" + tmp,
								"agent-client.junie.capture-enabled=false", "agent-client.junie.extras.k=v")),
				new Provider("qwen-code", QwenCodeAgentAutoConfiguration.class, QwenCodeAgentModel.class,
						AgentModel.class,
						() -> List.of("agent-client.qwen-code.model=m", "agent-client.qwen-code.timeout=30s",
								"agent-client.qwen-code.yolo=false",
								"agent-client.qwen-code.executable-path=" + cli("qwen"))))
			.map(Arguments::of);
	}

	private static ApplicationContextRunner runner(Provider provider) {
		// Gemini's agent requires a Sandbox bean; the others take one if present.
		return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(provider.configuration()))
			.withBean(Sandbox.class, () -> mock(Sandbox.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providers")
	void everyPropertyBindsAndTheAgentIsCreated(Provider provider) {
		runner(provider).withPropertyValues(provider.properties().get().toArray(String[]::new)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(provider.agentType());
			assertThat(context.getBean(AgentApi.class)).isInstanceOf(provider.agentType());
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providers")
	void theDefaultsCreateTheAgent(Provider provider) {
		List<String> executableOnly = new ArrayList<>();
		for (String property : provider.properties().get()) {
			if (property.contains(".executable-path=")) {
				executableOnly.add(property);
			}
		}
		runner(provider).withPropertyValues(executableOnly.toArray(String[]::new)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(provider.agentType());
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providers")
	void anApplicationAgentWins(Provider provider) {
		Object own = mock(provider.agentType());
		runner(provider).withPropertyValues(provider.properties().get().toArray(String[]::new))
			.withBean("applicationAgent", castType(provider.agentType()), () -> own)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(provider.agentType());
				assertThat(context.getBean(provider.agentType())).isSameAs(own);
			});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providers")
	void absentWithoutTheProvider(Provider provider) {
		runner(provider).withClassLoader(new FilteredClassLoader(provider.marker()))
			.run(context -> assertThat(context).doesNotHaveBean(AgentApi.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> Class<T> castType(Class<?> type) {
		return (Class<T>) type;
	}

	/**
	 * An executable placeholder: the auto-configurations need a path, and nothing runs
	 * it.
	 */
	private static String cli(String name) {
		try {
			Path file = tmp.resolve(name);
			if (!Files.exists(file)) {
				Files.writeString(file, "#!/bin/sh\nexit 0\n");
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
			}
			return file.toString();
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
