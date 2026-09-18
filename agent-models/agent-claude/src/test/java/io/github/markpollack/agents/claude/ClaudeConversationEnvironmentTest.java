/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

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
		logs.start();
		root.addAppender(logs);
		root.setLevel(Level.TRACE);
		try {
			for (var options : java.util.List.of(config.initial(), config.resumed())) {
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
