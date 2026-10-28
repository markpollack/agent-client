/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.AgentSessionStatus;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodexAgentSessionTest {

	private static final Path DIRECTORY = Path.of("/tmp/project with spaces");

	private static final String THREAD = "a75f5f43-62d7-480c-bfd0-0491e9ed3c0a";

	private static final String TOKEN = "test-secret-bearer";

	private static final McpServerDefinition HTTP = new McpServerDefinition.HttpDefinition("http://127.0.0.1:8123/mcp",
			Map.of("Authorization", "Bearer " + TOKEN));

	private static final class Registry extends CodexAgentSessionRegistry {

		final List<ExecuteOptions> options = new ArrayList<>();

		final List<CodexClient> clients = new ArrayList<>();

		Registry() {
			super(builder().approvedTools(Set.of("probe", "status")));
		}

		@Override
		CodexClient newClient(ExecuteOptions options, Path directory) {
			this.options.add(options);
			CodexClient client = mock(CodexClient.class);
			clients.add(client);
			when(client.stream(nullable(String.class), anyString(), any())).thenAnswer(call -> {
				Consumer<String> observer = call.getArgument(2);
				success(observer);
				return 0;
			});
			return client;
		}

	}

	private static void identity(Consumer<String> observer) {
		observer.accept("{\"type\":\"thread.started\",\"thread_id\":\"" + THREAD + "\"}");
	}

	private static void success(Consumer<String> observer) {
		identity(observer);
		observer
			.accept("""
					{"type":"item.started","item":{"id":"tool-1","type":"mcp_tool_call","server":"scoped","tool":"probe","arguments":{"nonce":"one"}}}
					""");
		observer
			.accept("""
					{"type":"item.completed","item":{"id":"tool-1","type":"mcp_tool_call","status":"completed","result":{"content":[{"type":"text","text":"receipt"}]},"error":null}}
					""");
		observer.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"answer\"}}");
		observer.accept("{\"type\":\"turn.completed\"}");
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({ org.junit.jupiter.api.condition.OS.LINUX,
			org.junit.jupiter.api.condition.OS.MAC })
	void definitionTravelsThroughRegistrySdkAndRealChildOnBothTurns(@org.junit.jupiter.api.io.TempDir Path directory)
			throws Exception {
		Path script = directory.resolve("fake-codex");
		java.nio.file.Files.writeString(script, """
				#!/bin/sh
				if [ "$1" = '--version' ]; then echo fixture; exit 0; fi
				env | grep -q '^AGENT_CLIENT_MCP_TOKEN_.*=test-secret-bearer$' || exit 11
				[ "$CONVERSATION_OPTION" = 'private-option-value' ] || exit 12
				[ "$SHELL" = '/conversation-shell' ] || exit 13
				printf '%s' "$PATH" > inherited-path.txt
				printf '%s\\n' "$@" >> argv.txt
				echo '{"type":"thread.started","thread_id":"fixture-thread"}'
				echo '{"type":"item.completed","item":{"type":"agent_message","text":"answer"}}'
				echo '{"type":"turn.completed"}'
				""");
		assertThat(script.toFile().setExecutable(true)).isTrue();
		var environment = new java.util.HashMap<>(
				Map.of("CONVERSATION_OPTION", "private-option-value", "SHELL", "/conversation-shell"));
		var registry = CodexAgentSessionRegistry.builder()
			.environmentVariables(environment)
			.codexPath(script.toString())
			.approvedTools(Set.of("probe"))
			.build();
		environment.put("CONVERSATION_OPTION", "changed-after-build");
		try (var session = registry.create(directory, "scoped", HTTP)) {
			assertThat(session.prompt("first").getText()).isEqualTo("answer");
			assertThat(session.prompt("second").getText()).isEqualTo("answer");
		}
		assertThat(java.nio.file.Files.readString(directory.resolve("inherited-path.txt")))
			.isEqualTo(System.getenv("PATH"));
		var argv = java.nio.file.Files.readAllLines(directory.resolve("argv.txt"));
		assertThat(argv.stream().filter(arg -> arg.equals("mcp_servers.scoped.url=\"http://127.0.0.1:8123/mcp\"")))
			.hasSize(2);
		assertThat(argv.stream().filter(arg -> arg.startsWith("mcp_servers.scoped.bearer_token_env_var="))).hasSize(2);
		assertThat(argv).containsSequence("exec", "resume", "--json", "fixture-thread", "--", "second")
			.noneMatch(arg -> arg.contains(TOKEN) || arg.contains("private-option-value"));
	}

	@Test
	void twoTurnsRetainDefinitionAndExactThreadAtRealCommandBoundary() throws Exception {
		Registry registry = new Registry();
		AgentSession session = registry.create(DIRECTORY, "scoped", HTTP);
		String id = session.getSessionId();
		CodexClient client = registry.clients.getFirst();
		verifyNoInteractions(client);
		var events = new ArrayList<AgentSessionEvent>();
		assertThat(session.prompt("first", events::add).isSuccessful()).isTrue();
		assertThat(session.prompt("second").getText()).isEqualTo("answer");
		verify(client).stream(isNull(), eq("first"), any());
		verify(client).stream(eq(THREAD), eq("second"), any());
		assertThat(session.getSessionId()).isEqualTo(id);
		assertThat(registry.find(id)).contains(session);
		assertThat(events).extracting(Object::getClass)
			.containsExactly(AgentSessionEvent.ToolCall.class, AgentSessionEvent.ToolResult.class,
					AgentSessionEvent.Text.class, AgentSessionEvent.Terminal.class);
		ExecuteOptions options = registry.options.getFirst();
		assertThat(options.getEnvironment()).containsValue(TOKEN).hasSize(1);
		String variable = options.getEnvironment().keySet().iterator().next();
		var method = io.github.markpollack.agents.codexsdk.transport.CLITransport.class
			.getDeclaredMethod("buildCommand", String.class, String.class, ExecuteOptions.class, String.class);
		method.setAccessible(true);
		for (String thread : new String[] { null, THREAD }) {
			@SuppressWarnings("unchecked")
			List<String> argv = (List<String>) method.invoke(null, "codex", "prompt", options, thread);
			assertThat(argv).containsSequence("-c", "mcp_servers.scoped.url=\"http://127.0.0.1:8123/mcp\"")
				.containsSequence("-c", "mcp_servers.scoped.bearer_token_env_var=\"" + variable + "\"")
				.containsSequence("-c", "mcp_servers.scoped.tools.probe.approval_mode=\"approve\"")
				.containsSequence("-c", "mcp_servers.scoped.tools.status.approval_mode=\"approve\"")
				.containsSequence("-c", "mcp_servers.scoped.required=true")
				.containsSequence("--sandbox", "read-only")
				.containsSequence("--ask-for-approval", "never")
				.contains("--json")
				.noneMatch(arg -> arg.contains(TOKEN));
			if (thread != null) {
				assertThat(argv).containsSequence("exec", "resume").containsSequence(THREAD, "--", "prompt");
			}
		}
		assertThat(registry.options).hasSize(1);
	}

	@Test
	void rejectsUnsupportedDefinitionsHeadersUrlsAndMissingApprovals() {
		Registry registry = new Registry();
		assertThatThrownBy(() -> registry.create(DIRECTORY, "scoped", new McpServerDefinition.StdioDefinition("echo")))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> registry.create(DIRECTORY, "bad.name", HTTP))
			.isInstanceOf(IllegalArgumentException.class);
		for (String url : List.of("file:///secret", "https://user:pass@host/mcp", "http://host/mcp?token=secret",
				"not a url")) {
			assertThatThrownBy(() -> registry.create(DIRECTORY, "scoped", new McpServerDefinition.HttpDefinition(url)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(url);
		}
		assertThatThrownBy(() -> registry.create(DIRECTORY, "scoped",
				new McpServerDefinition.HttpDefinition("http://host/mcp", Map.of("X-Token", TOKEN))))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageNotContaining(TOKEN);
		assertThatThrownBy(() -> CodexSessionConfiguration.create(DIRECTORY, null, Duration.ofSeconds(1), "scoped",
				HTTP, Set.of(), Map.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("approved tool names");
		assertThat(registry.clients).isEmpty();
	}

	@Test
	void rejectedConfigurationFailsExplicitlyWithoutLeakingDiagnostics() {
		Registry registry = new Registry();
		AgentSession session = registry.create(DIRECTORY, "scoped", HTTP);
		doAnswer(call -> {
			Consumer<String> observer = call.getArgument(2);
			observer.accept("Unknown configuration " + TOKEN);
			return 2;
		}).when(registry.clients.getFirst()).stream(any(), anyString(), any());
		var events = new ArrayList<AgentSessionEvent>();
		assertThatThrownBy(() -> session.prompt("first", events::add)).hasMessageContaining("configuration")
			.hasMessageNotContaining(TOKEN);
		assertThat(events).containsExactly(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.ERROR));
		assertThat(session.getStatus()).isEqualTo(AgentSessionStatus.DEAD);
		assertThatThrownBy(session::resume).hasMessageContaining("thread ID");
	}

	@Test
	void differentThreadAndTruncatedOrFailedStreamCannotSucceed() {
		for (String event : List.of("{\"type\":\"thread.started\",\"thread_id\":\"different\"}",
				"{\"type\":\"turn.failed\"}", "{", "{}")) {
			Registry registry = new Registry();
			AgentSession session = registry.create(DIRECTORY);
			session.prompt("first");
			doAnswer(call -> {
				Consumer<String> observer = call.getArgument(2);
				observer.accept(event);
				return 0;
			}).when(registry.clients.getFirst()).stream(any(), anyString(), any());
			assertThatThrownBy(() -> session.prompt("second")).isInstanceOf(IllegalStateException.class);
			assertThat(session.getStatus()).isEqualTo(AgentSessionStatus.DEAD);
		}
	}

	@Test
	void cancelIsIsolatedAndResumeRetainsIdentityAndConfiguration() throws Exception {
		Registry registry = new Registry();
		AgentSession session = registry.create(DIRECTORY, "scoped", HTTP);
		AgentSession other = registry.create(DIRECTORY, "scoped", HTTP);
		CountDownLatch started = new CountDownLatch(1);
		CodexClient client = registry.clients.getFirst();
		doAnswer(call -> {
			Consumer<String> observer = call.getArgument(2);
			identity(observer);
			started.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				throw new IllegalStateException("interrupted");
			}
			return 0;
		}).when(client).stream(any(), anyString(), any());
		var events = new ArrayList<AgentSessionEvent>();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var pending = executor.submit(() -> session.prompt("wait", events::add));
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> session.prompt("overlap")).hasMessageContaining("already active");
			assertThatThrownBy(session::resume).isInstanceOf(IllegalStateException.class);
			session.cancelActiveTurn();
			session.cancelActiveTurn();
			assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
		}
		assertThat(events.getLast()).isEqualTo(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.CANCELLED));
		assertThat(other.prompt("unaffected").isSuccessful()).isTrue();
		session.resume();
		doAnswer(call -> {
			success(call.getArgument(2));
			return 0;
		}).when(client).stream(any(), anyString(), any());
		assertThat(session.prompt("again").isSuccessful()).isTrue();
		verify(client).stream(eq(THREAD), eq("again"), any());
		assertThat(registry.options).hasSize(2);
		assertThat(registry.options.get(0).getEnvironment().keySet())
			.doesNotContainAnyElementsOf(registry.options.get(1).getEnvironment().keySet());
	}

	@Test
	void observerFailureClosesTurnAndCloseIsTerminal() {
		Registry registry = new Registry();
		AgentSession session = registry.create(DIRECTORY);
		assertThatThrownBy(() -> session.prompt("first", event -> {
			if (event instanceof AgentSessionEvent.Text) {
				throw new IllegalStateException("observer failed");
			}
		})).hasMessage("observer failed");
		session.resume();
		assertThat(session.prompt("next").isSuccessful()).isTrue();
		session.close();
		session.close();
		assertThatThrownBy(() -> session.prompt("closed")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(session::resume).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(session::fork).isInstanceOf(UnsupportedOperationException.class);
		registry.evict(session.getSessionId());
		assertThat(registry.find(session.getSessionId())).isEmpty();
	}

	@Test
	void terminalObserverFailureAndReentryDoNotLeaveTurnLocked() {
		Registry registry = new Registry();
		AgentSession session = registry.create(DIRECTORY);
		assertThatThrownBy(() -> session.prompt("first", event -> {
			if (event instanceof AgentSessionEvent.Terminal) {
				session.prompt("reentry");
			}
		})).hasMessageContaining("already active");
		session.resume();
		assertThat(session.prompt("next").isSuccessful()).isTrue();
	}

}
