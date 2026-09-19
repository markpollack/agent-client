/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.markpollack.claude.agent.sdk.transport.StreamingTransport;

import static org.assertj.core.api.Assertions.*;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class ClaudeConversationEnvironmentTest {

	@TempDir
	Path directory;

	@Test
	void initialAndResumedSdkChildrenReceiveImmutableOverridesWithoutLoggingValues() throws Exception {
		var environment = new java.util.HashMap<>(
				Map.of("MCP_TOOL_TIMEOUT", "246813", "PATH", "/conversation-private-path"));
		var defaults = ClaudeAgentOptions.builder().environmentVariables(environment).build();
		try (var config = ClaudeSessionConfiguration.create(directory, "fixture-session", defaults,
				Duration.ofSeconds(5), null, null)) {
			environment.put("MCP_TOOL_TIMEOUT", "changed-after-open");
			assertChildEnvironment(config, "246813", "/conversation-private-path");
		}
	}

	@Test
	void absentOptionsPreserveInheritedEnvironmentOnInitialAndResume() throws Exception {
		try (var config = ClaudeSessionConfiguration.create(directory, "fixture-session", null, Duration.ofSeconds(5),
				null, null)) {
			assertThat(config.initial().env()).isEmpty();
			assertThat(config.resumed().env()).isEmpty();
			assertChildEnvironment(config, System.getenv().getOrDefault("MCP_TOOL_TIMEOUT", "unset"),
					System.getenv("PATH"));
		}
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void cancelledTurnResumesWithScopedToolsAndEnvironmentWithoutReplay(boolean currentBinding) throws Exception {
		Path executable = directory.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/usr/bin/python3
						import json, os, sys
						args = sys.argv[1:]
						resumed = '--resume' in args
						phase = 'resumed' if resumed else 'initial'
						identity = args[args.index('--resume' if resumed else '--session-id') + 1]
						config = args[args.index('--mcp-config') + 1]
						with open(config) as source:
						    servers = json.load(source)
						with open(phase + '.json', 'w') as output:
						    json.dump({'pid':os.getpid(), 'argv': args, 'config': servers, 'budget': os.environ.get('MCP_TOOL_TIMEOUT')}, output)
						def emit(value):
						    print(json.dumps(value), flush=True)
						emit({'type':'system','subtype':'init','mcp_servers':[{'name':'scoped','status':'connected'}]})
						for line in sys.stdin:
						    with open(phase + '-prompts.jsonl', 'a') as output:
						        output.write(line)
						    emit({'type':'assistant','message':{'role':'assistant','content':[{'type':'tool_use','id':'call-1','name':'mcp__scoped__lookup','input':{'nonce':phase}}]}})
						    if resumed:
						        emit({'type':'user','message':{'role':'user','content':[{'type':'tool_result','tool_use_id':'call-1','content':'fresh-receipt'}]}})
						        emit({'type':'assistant','message':{'role':'assistant','content':[{'type':'text','text':'fresh-receipt'}]}})
						        emit({'type':'result','subtype':'success','is_error':False,'session_id':identity,'result':'fresh-receipt'})
						""");
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		var definition = new McpServerDefinition.HttpDefinition("http://127.0.0.1:12345/mcp",
				Map.of("Authorization", "Bearer fixture-token"));
		var registry = ClaudeAgentSessionRegistry.builder()
			.claudePath(executable.toString())
			.defaultOptions(
					ClaudeAgentOptions.builder().environmentVariables(Map.of("MCP_TOOL_TIMEOUT", "246813")).build())
			.build();
		Path config;
		try (var executor = Executors.newSingleThreadExecutor();
				var session = registry.create(directory, "scoped", definition)) {
			String identity = session.getSessionId();
			var entered = new CountDownLatch(1);
			var pending = executor.submit(() -> session.prompt("cancel-this-prompt", event -> {
				if (event instanceof AgentSessionEvent.ToolCall) {
					entered.countDown();
				}
			}));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			session.cancelActiveTurn();
			assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
			var mapper = new ObjectMapper();
			var initial = mapper.readTree(directory.resolve("initial.json").toFile());
			assertThat(ProcessHandle.of(initial.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false))
				.isFalse();
			if (currentBinding) {
				session.resume(new McpServerDefinition.HttpDefinition("http://127.0.0.1:12345/current",
						Map.of("Authorization", "Bearer current-token")), Map.of("MCP_TOOL_TIMEOUT", "97531"));
			}
			else {
				session.resume();
			}
			var events = new ArrayList<AgentSessionEvent>();
			assertThat(session.prompt("next-tool-prompt", events::add).getText()).contains("fresh-receipt");
			assertThat(session.getSessionId()).isEqualTo(identity);
			assertThat(events).extracting(Object::getClass)
				.containsExactly(AgentSessionEvent.ToolCall.class, AgentSessionEvent.ToolResult.class,
						AgentSessionEvent.Text.class, AgentSessionEvent.Terminal.class);
			var resumed = mapper.readTree(directory.resolve("resumed.json").toFile());
			var initialArgs = mapper.convertValue(initial.path("argv"), new TypeReference<List<String>>() {
			});
			Path oldConfig = Path.of(initialArgs.get(initialArgs.indexOf("--mcp-config") + 1));
			if (currentBinding) {
				assertThat(oldConfig).doesNotExist();
			}
			else {
				assertThat(resumed.path("config")).isEqualTo(initial.path("config"));
			}
			var server = resumed.path("config").path("mcpServers").path("scoped");
			assertThat(server.path("url").asText())
				.isEqualTo(currentBinding ? "http://127.0.0.1:12345/current" : "http://127.0.0.1:12345/mcp");
			assertThat(server.path("headers").path("Authorization").asText())
				.isEqualTo(currentBinding ? "Bearer current-token" : "Bearer fixture-token");
			assertThat(initial.path("budget").asText()).isEqualTo("246813");
			assertThat(resumed.path("budget").asText()).isEqualTo(currentBinding ? "97531" : "246813");
			var argv = mapper.convertValue(resumed.path("argv"), new TypeReference<List<String>>() {
			});
			assertThat(argv).containsSequence("--resume", identity).doesNotContain("--session-id");
			config = Path.of(argv.get(argv.indexOf("--mcp-config") + 1));
			assertThat(config).exists();
			var prompts = Files.readAllLines(directory.resolve("resumed-prompts.jsonl"));
			assertThat(prompts).hasSize(1);
			assertThat(mapper.readTree(prompts.getFirst()).path("message").path("content").asText())
				.isEqualTo("next-tool-prompt");
			assertThat(Files.readAllLines(directory.resolve("initial-prompts.jsonl"))).hasSize(1);
		}
		assertThat(config).doesNotExist();
	}

	private void assertChildEnvironment(ClaudeSessionConfiguration config, String budget, String path)
			throws Exception {
		Path executable = directory.resolve("fake-claude");
		Files.writeString(executable, """
				#!/bin/sh
				printf '%s' "${MCP_TOOL_TIMEOUT-unset}" > observed-budget
				printf '%s' "$PATH" > observed-path
				echo '{"type":"system","subtype":"init"}'
				while IFS= read -r line; do :; done
				""");
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		Level previous = root.getLevel();
		var logs = new ListAppender<ILoggingEvent>();
		logs.list = new java.util.concurrent.CopyOnWriteArrayList<>();
		logs.start();
		root.addAppender(logs);
		root.setLevel(Level.TRACE);
		try {
			for (var options : List.of(config.initial(), config.resumed())) {
				Files.deleteIfExists(directory.resolve("observed-budget"));
				Files.deleteIfExists(directory.resolve("observed-path"));
				var ready = new CountDownLatch(1);
				try (var transport = new StreamingTransport(directory, Duration.ofSeconds(5), executable.toString())) {
					transport.startSession(null, options, message -> ready.countDown(), null, null);
					assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
					assertThat(Files.readString(directory.resolve("observed-budget"))).isEqualTo(budget);
					assertThat(Files.readString(directory.resolve("observed-path"))).isEqualTo(path);
				}
			}
			assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("246813")
					|| event.getFormattedMessage().contains("/conversation-private-path"));
		}
		finally {
			root.setLevel(previous);
			root.detachAppender(logs);
			logs.stop();
		}
	}

}
