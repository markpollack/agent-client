/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codexsdk.transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;

import static org.assertj.core.api.Assertions.*;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class CLITransportStreamTest {

	@TempDir
	Path directory;

	private CLITransport fixture(String body) throws Exception {
		Path executable = directory.resolve("fake codex");
		Files.writeString(executable, "#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo fixture; exit 0; fi\n" + body);
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		return new CLITransport(directory, executable.toString());
	}

	@Test
	void realChildReceivesOverridesAndSecretEnvironmentOnInitialAndResumeWithoutLoggingSecret() throws Exception {
		var transport = fixture("""
				[ "$TEST_MCP_BEARER" = 'fixture-private-token' ] || exit 10
				printf '%s\\n' "$@" > argv.txt
				echo "$TEST_MCP_BEARER"
				echo complete
				""");
		ExecuteOptions options = ExecuteOptions.builder()
			.model(null)
			.sandboxMode(SandboxMode.READ_ONLY)
			.jsonOutput(true)
			.workingDirectory(directory)
			.environment(Map.of("TEST_MCP_BEARER", "fixture-private-token"))
			.configOverrides(Map.of("mcp_servers.scoped.url", "\"http://127.0.0.1:8888/mcp\"",
					"mcp_servers.scoped.bearer_token_env_var", "\"TEST_MCP_BEARER\"",
					"mcp_servers.scoped.tools.probe.approval_mode", "\"approve\""))
			.build();
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		Level previous = root.getLevel();
		var logs = new ListAppender<ILoggingEvent>();
		logs.start();
		root.addAppender(logs);
		root.setLevel(Level.TRACE);
		try {
			for (String id : new String[] { null, "exact-thread" }) {
				var lines = new ArrayList<String>();
				assertThat(transport.stream("prompt", options, id, lines::add)).isZero();
				assertThat(lines).containsExactly("[REDACTED]", "complete");
				var argv = Files.readAllLines(directory.resolve("argv.txt"));
				assertThat(argv).containsSequence("-c", "mcp_servers.scoped.url=\"http://127.0.0.1:8888/mcp\"")
					.containsSequence("-c", "mcp_servers.scoped.bearer_token_env_var=\"TEST_MCP_BEARER\"")
					.containsSequence("-c", "mcp_servers.scoped.tools.probe.approval_mode=\"approve\"")
					.containsSequence("--sandbox", "read-only")
					.containsSequence("--ask-for-approval", "never")
					.doesNotContain("fixture-private-token", "--last");
				if (id != null) {
					assertThat(argv).containsSequence("exec", "resume", "--json", id, "--", "prompt");
				}
			}
			assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("fixture-private-token"));
		}
		finally {
			root.setLevel(previous);
			root.detachAppender(logs);
			logs.stop();
		}
	}

	@Test
	void observerReceivesOutputBeforeChildExitAndFailureStopsChild() throws Exception {
		var transport = fixture("echo $$\nwhile :; do :; done\n");
		AtomicLong pid = new AtomicLong();
		assertThatThrownBy(() -> transport.stream("prompt", ExecuteOptions.defaultOptions(), null, line -> {
			pid.set(Long.parseLong(line));
			assertThat(ProcessHandle.of(pid.get()).orElseThrow().isAlive()).isTrue();
			throw new IllegalStateException("observer failed");
		})).hasMessage("observer failed");
		assertStopped(pid.get());
	}

	@Test
	void interruptStopsOnlyTheActiveChild() throws Exception {
		var transport = fixture("echo $$\nwhile :; do :; done\n");
		AtomicLong pid = new AtomicLong();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var pending = executor.submit(() -> {
				try {
					assertThatThrownBy(() -> transport.stream("prompt", ExecuteOptions.defaultOptions(), null, line -> {
						pid.set(Long.parseLong(line));
						started.countDown();
					})).hasMessageContaining("interrupted");
				}
				finally {
					finished.countDown();
				}
			});
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			pending.cancel(true);
			assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
		}
		assertStopped(pid.get());
	}

	@Test
	void timeoutStopsChildAndReportsSanitizedFailure() throws Exception {
		var transport = fixture("echo $$\nwhile :; do :; done\n");
		AtomicLong pid = new AtomicLong();
		var options = ExecuteOptions.builder()
			.timeout(Duration.ofMillis(200))
			.environment(Map.of("TEST_MCP_BEARER", "fixture-private-token"))
			.build();
		assertThatThrownBy(() -> transport.stream("prompt", options, null, line -> pid.set(Long.parseLong(line))))
			.hasMessageContaining("timed out")
			.hasMessageNotContaining("fixture-private-token");
		assertStopped(pid.get());
	}

	private void assertStopped(long pid) throws Exception {
		assertThat(pid).isPositive();
		var child = ProcessHandle.of(pid);
		if (child.isPresent()) {
			child.get().onExit().get(5, TimeUnit.SECONDS);
		}
		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
	}

}
