/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.model.AgentSession;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.AgentSessionStatus;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeAgentSessionTest {

	@TempDir
	Path directory;

	final McpServerDefinition definition = new McpServerDefinition.HttpDefinition("http://127.0.0.1:12345/mcp",
			Map.of("Authorization", "Bearer scoped-test"));

	@Test
	void configurationReachesSdkAndTwoTurnsShareClientAndEvents() throws Exception {
		var defaults = ClaudeAgentOptions.builder()
			.model("test-model")
			.mcpServers(Map.of("unrelated", new McpServerConfig.McpStdioServerConfig("existing", List.of(), Map.of())))
			.build();
		var registry = new TestRegistry(defaults);
		Path config;
		try (var session = registry.create(directory, "scoped", definition)) {
			verify(registry.client).connect();
			verify(registry.client, never()).receiveResponse();
			CLIOptions options = registry.options.getFirst();
			config = Path.of(options.extraArgs().get("mcp-config"));
			var json = new ObjectMapper().readTree(Files.readString(config)).path("mcpServers");
			assertThat(json.path("scoped").path("url").asText()).isEqualTo("http://127.0.0.1:12345/mcp");
			assertThat(json.path("scoped").path("type").asText()).isEqualTo("http");
			assertThat(json.path("scoped").path("headers").path("Authorization").asText())
				.isEqualTo("Bearer scoped-test");
			assertThat(json.path("unrelated").path("command").asText()).isEqualTo("existing");
			assertThat(options.extraArgs()).doesNotContainKey("strict-mcp-config");
			assertThat(options.extraArgs()).containsEntry("session-id", session.getSessionId());
			defaults.setModel("changed-after-open");
			doAnswer(invocation -> response(session.getSessionId(), false)).when(registry.client).receiveResponse();
			var events = new ArrayList<AgentSessionEvent>();
			assertThat(session.prompt("first", events::add).getResult().getOutput()).contains("receipt");
			assertThat(session.prompt("second").getResult().getOutput()).contains("receipt");
			assertThat(events).containsExactly(
					new AgentSessionEvent.ToolCall("call-1", "mcp__scoped__lookup", Map.of("nonce", "n1")),
					new AgentSessionEvent.ToolResult("call-1", "receipt", false), new AgentSessionEvent.Text("receipt"),
					new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.SUCCESS));
			verify(registry.client).query("first", session.getSessionId());
			verify(registry.client).query("second", session.getSessionId());
			assertThat(registry.options).hasSize(1);
			assertThat(options.model()).isEqualTo("test-model");
		}
		assertThat(config).doesNotExist();
		verify(registry.client).close();
	}

	@Test
	void transportFailureResumesSameIdAndConfigurationWithoutPaidPrompt() throws Exception {
		var defaults = ClaudeAgentOptions.builder()
			.environmentVariables(new java.util.HashMap<>(Map.of("MCP_TOOL_TIMEOUT", "246813")))
			.build();
		var registry = new TestRegistry(defaults);
		try (var session = registry.create(directory, "scoped", definition)) {
			assertThat(registry.options.getFirst().env()).containsEntry("MCP_TOOL_TIMEOUT", "246813");
			defaults.getEnvironmentVariables().put("MCP_TOOL_TIMEOUT", "changed-after-open");
			when(registry.client.receiveResponse()).thenThrow(new IllegalStateException("transport failed"));
			assertThatThrownBy(() -> session.prompt("first")).isInstanceOf(IllegalStateException.class);
			assertThat(session.getStatus()).isEqualTo(AgentSessionStatus.DEAD);
			session.resume();
			assertThat(registry.options).hasSize(2);
			assertThat(registry.options.get(1).env()).containsEntry("MCP_TOOL_TIMEOUT", "246813");
			assertThat(registry.options.get(1).resume()).isEqualTo(session.getSessionId());
			assertThat(registry.options.get(1).extraArgs())
				.containsEntry("mcp-config", registry.options.getFirst().extraArgs().get("mcp-config"))
				.doesNotContainKey("session-id");
			doAnswer(invocation -> response(session.getSessionId(), false)).when(registry.client).receiveResponse();
			session.prompt("second");
			verify(registry.client, times(2)).receiveResponse();
		}
	}

	@Test
	void failedEndpointIsExplicitAndClosesClient() throws Exception {
		var registry = new TestRegistry(null);
		try (var session = registry.create(directory, "scoped", definition)) {
			when(registry.client.receiveResponse())
				.thenReturn(List.<ParsedMessage>of(new ParsedMessage.RegularMessage(SystemMessage.of("init",
						Map.of("mcp_servers", List.of(Map.of("name", "scoped", "status", "failed"))))))
					.iterator());
			var events = new ArrayList<AgentSessionEvent>();
			assertThatThrownBy(() -> session.prompt("first", events::add))
				.hasRootCauseMessage("Claude did not connect the scoped MCP server: scoped");
			assertThat(events).containsExactly(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.ERROR));
			verify(registry.client).close();
		}
	}

	@Test
	void errorResultIsNotReportedAsSuccess() throws Exception {
		var registry = new TestRegistry(null);
		try (var session = registry.create(directory)) {
			when(registry.client.receiveResponse()).thenAnswer(invocation -> response(session.getSessionId(), true));
			var events = new ArrayList<AgentSessionEvent>();
			assertThat(session.prompt("first", events::add).getResult().getMetadata().getFinishReason())
				.isEqualTo("ERROR");
			assertThat(events.getLast()).isEqualTo(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.ERROR));
		}
	}

	@Test
	void cancellationRejectsOverlapAndReleasesOnlyThisSession() throws Exception {
		var registry = new TestRegistry(null);
		var otherRegistry = new TestRegistry(null);
		try (var session = registry.create(directory, "scoped", definition);
				var other = otherRegistry.create(directory, "scoped", definition);
				var executor = Executors.newSingleThreadExecutor()) {
			var entered = new CountDownLatch(1);
			var released = new CountDownLatch(1);
			when(registry.client.receiveResponse()).thenAnswer(invocation -> {
				entered.countDown();
				assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
				return List.<ParsedMessage>of().iterator();
			});
			doAnswer(invocation -> {
				released.countDown();
				return null;
			}).when(registry.client).close();
			var events = new ArrayList<AgentSessionEvent>();
			var future = executor.submit(() -> session.prompt("blocked", events::add));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> session.prompt("overlap")).hasMessageContaining("already active");
			assertThatThrownBy(() -> session.resume(definition, Map.of())).isInstanceOf(IllegalStateException.class);
			session.cancelActiveTurn();
			session.cancelActiveTurn();
			assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
			assertThat(events).containsExactly(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.CANCELLED));
			verify(otherRegistry.client, never()).close();
			session.resume();
			doAnswer(invocation -> response(session.getSessionId(), false)).when(registry.client).receiveResponse();
			session.prompt("after cancellation");
		}
	}

	@Test
	void closeIsTerminalAndIdleCancellationIsHarmless() throws Exception {
		var registry = new TestRegistry(null);
		var session = registry.create(directory, "scoped", definition);
		session.cancelActiveTurn();
		verify(registry.client, never()).close();
		session.close();
		session.close();
		session.cancelActiveTurn();
		verify(registry.client).close();
		assertThatThrownBy(session::resume).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> session.resume(definition, Map.of())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> session.prompt("closed")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void openFailureClosesClientAndDeletesConfig() throws Exception {
		var registry = new TestRegistry(null);
		doThrow(new IllegalStateException("launch failed")).when(registry.client).connect();
		assertThatThrownBy(() -> registry.create(directory, "scoped", definition))
			.hasMessageContaining("Failed to open");
		verify(registry.client).close();
		assertThat(Path.of(registry.options.getFirst().extraArgs().get("mcp-config"))).doesNotExist();
		assertThat(registry.size()).isZero();
	}

	@Test
	void rejectsNameCollisionBeforeLaunching() {
		var registry = new TestRegistry(ClaudeAgentOptions.builder()
			.mcpServers(Map.of("scoped", new McpServerConfig.McpStdioServerConfig("existing")))
			.build());
		assertThatThrownBy(() -> registry.create(directory, "scoped", definition))
			.hasRootCauseMessage("Scoped MCP server name conflicts with a configured server: scoped");
		assertThat(registry.options).isEmpty();
	}

	@Test
	void observerFailureClosesTransportAndMissingTerminalIsFailure() throws Exception {
		var registry = new TestRegistry(null);
		try (var session = registry.create(directory)) {
			doAnswer(invocation -> response(session.getSessionId(), false)).when(registry.client).receiveResponse();
			assertThatThrownBy(() -> session.prompt("first", event -> {
				if (event instanceof AgentSessionEvent.Text) {
					throw new IllegalArgumentException("observer failed");
				}
			})).hasRootCauseMessage("observer failed");
			verify(registry.client).close();
			session.resume();
			when(registry.client.receiveResponse()).thenReturn(List.<ParsedMessage>of().iterator());
			assertThatThrownBy(() -> session.prompt("empty"))
				.hasRootCauseMessage("Claude stream ended without a terminal result");
		}
	}

	@Test
	void invalidEndpointAndMissingConfigurationConfirmationFailExplicitly() throws Exception {
		var registry = new TestRegistry(null);
		assertThatThrownBy(
				() -> registry.create(directory, "scoped", new McpServerDefinition.HttpDefinition("file:///tmp/mcp")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(registry.options).isEmpty();
		try (var session = registry.create(directory, "scoped", definition)) {
			when(registry.client.receiveResponse()).thenReturn(List.<ParsedMessage>of(
					new ParsedMessage.RegularMessage(ResultMessage.builder().sessionId(session.getSessionId()).build()))
				.iterator());
			assertThatThrownBy(() -> session.prompt("first"))
				.hasRootCauseMessage("Claude did not confirm the scoped MCP configuration");
		}
	}

	@Test
	void sdkCommandContainsScopedFileOnInitialAndResumedInvocations() throws Exception {
		try (var config = ClaudeSessionConfiguration.create(directory, "session-id", null,
				java.time.Duration.ofSeconds(5), "scoped", definition);
				var transport = new io.github.markpollack.claude.agent.sdk.transport.StreamingTransport(directory,
						java.time.Duration.ofSeconds(5), "unused-claude")) {
			var method = transport.getClass().getDeclaredMethod("buildStreamingCommand", CLIOptions.class);
			method.setAccessible(true);
			for (CLIOptions options : List.of(config.initial(), config.resumed())) {
				@SuppressWarnings("unchecked")
				var command = (List<String>) method.invoke(transport, options);
				assertThat(command).containsSubsequence("--mcp-config", config.file().toString())
					.doesNotContain("--strict-mcp-config", "--setting-sources", "--dangerously-skip-permissions");
				assertThat(command.stream().filter("--mcp-config"::equals)).hasSize(1);
			}
		}
	}

	static Iterator<ParsedMessage> response(String id, boolean error) {
		return List.<ParsedMessage>of(
				new ParsedMessage.RegularMessage(SystemMessage.of("init",
						Map.of("mcp_servers", List.of(Map.of("name", "scoped", "status", "connected"))))),
				new ParsedMessage.RegularMessage(new AssistantMessage(
						List.of(new ToolUseBlock("call-1", "mcp__scoped__lookup", Map.of("nonce", "n1"))))),
				new ParsedMessage.RegularMessage(
						UserMessage.of(List.of(new ToolResultBlock("call-1", "receipt", false)))),
				new ParsedMessage.RegularMessage(new AssistantMessage(List.of(new TextBlock("receipt")))),
				new ParsedMessage.RegularMessage(ResultMessage.builder()
					.sessionId(id)
					.subtype(error ? "error" : "success")
					.isError(error)
					.result("receipt")
					.build()))
			.iterator();
	}

	static class TestRegistry extends ClaudeAgentSessionRegistry {

		final ClaudeSyncClient client = mock(ClaudeSyncClient.class);

		final List<CLIOptions> options = new ArrayList<>();

		TestRegistry(ClaudeAgentOptions defaults) {
			super(ClaudeAgentSessionRegistry.builder().defaultOptions(defaults));
		}

		@Override
		ClaudeSyncClient newClient(CLIOptions options, Path directory) {
			this.options.add(options);
			return client;
		}

	}

}
