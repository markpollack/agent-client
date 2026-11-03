/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentTaskRequest;
import static org.assertj.core.api.Assertions.*;

@Timeout(25)
class CodexClientTest {

	@TempDir
	Path root;

	Path script;

	@BeforeEach
	void setup() throws Exception {
		this.script = root.resolve("child.py");
		try (var in = getClass().getResourceAsStream("/codex-child.py")) {
			Files.copy(in, script);
		}
		try (var in = getClass().getResourceAsStream("/codex-native-template.jsonl")) {
			Files.copy(in, root.resolve("codex-native-template.jsonl"));
		}
		Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
	}

	private CodexClientProvider provider() {
		return CodexClientProvider.builder()
			.codexPath(script.toString())
			.workingDirectory(root)
			.nativeSessionsDirectory(root.resolve("sessions"))
			.acquisitionDirectory(root.resolve("captures"), "0.166.0")
			.skipGitCheck(true)
			.build();
	}

	private DefaultAgentOptions options() {
		return DefaultAgentOptions.builder()
			.workingDirectory(root.toString())
			.timeout(Duration.ofSeconds(8))
			.environmentVariables(Map.of("FIXTURE_SESSIONS", root.resolve("sessions").toString()))
			.model("fixture-model")
			.effort("low")
			.build();
	}

	private AgentClient.Builder client() {
		return AgentClient.builder(provider()).defaultOptions(options()).defaultWorkingDirectory(root);
	}

	@Test
	void successHasExactCwdArgvAndOriginalBytesWithIndependentRecording() throws Exception {
		Path cwd = Files.createDirectory(root.resolve("requested"));
		var evidence = new CompletableFuture<CodexAcquisition>();
		var release = new CountDownLatch(1);
		ExecutionRecorder recorder = recorder(evidence, release);
		try (var client = client().executionRecorder(recorder).build()) {
			var execution = client.goal("hello:π\nsecond line").workingDirectory(cwd).runAsync();
			assertThat(execution.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:hello:π\nsecond line");
			var d = execution.decision().orElseThrow();
			var a = evidence.get(3, TimeUnit.SECONDS);
			assertThat(a.complete()).as(a.detail()).isTrue();
			assertThat(a.executionId()).isEqualTo(d.executionId());
			assertThat(a.connectionId()).isEqualTo(d.connectionId().orElseThrow());
			assertThat(a.sources()).hasSize(4);
			var manifest = a.writeManifest(d);
			assertThat(a.writeManifest(d)).isEqualTo(manifest);
			var decoded = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(manifest));
			assertThat(decoded.path("process").path("client_session_uid").asText()).isEqualTo(d.sessionId());
			assertThat(decoded.path("turns").get(0).path("decision_uid").asText()).isEqualTo(d.decisionId());
			assertThat(decoded.path("turns").get(0).path("native_turn_uid").asText()).isNotBlank();
			assertThat(Files.readAllBytes(a.directory().resolve("stdin.bin")))
				.isEqualTo(Files.readAllBytes(cwd.resolve("prompt.bin")));
			assertThat(Files.readAllBytes(a.directory().resolve("stderr.bin"))).contains((byte) 0xff);
			assertThat(Files.readString(cwd.resolve("launches.jsonl"))).contains("--no-daemon", "--ignore-user-config",
					"--json", "--model", "fixture-model", cwd.toString(), "--ask-for-approval", "never", "read-only");
			assertThat(root.resolve("pid")).doesNotExist();
			release.countDown();
			assertThat(execution.awaitTermination(Duration.ofSeconds(3)).cleanupConfirmed()).isTrue();
			assertThat(execution.decision()).contains(d);
		}
		finally {
			release.countDown();
		}
		assertDead(cwd);
	}

	@ParameterizedTest
	@ValueSource(strings = { "wrong", "missing", "incomplete" })
	void incorrectOrIncompleteNativeCaptureDoesNotChangeAcceptedResult(String mode) throws Exception {
		var evidence = new CompletableFuture<CodexAcquisition>();
		try (var client = client().executionRecorder(recorder(evidence, new CountDownLatch(0))).build()) {
			var execution = client.goal(mode).runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			assertThat(evidence.get(3, TimeUnit.SECONDS).complete()).isFalse();
		}
		assertDead(root);
	}

	@ParameterizedTest
	@ValueSource(strings = { "eof", "duplicate", "malformed" })
	void invalidTerminalFailsAndNeverReplays(String mode) throws Exception {
		try (var client = client().build()) {
			var execution = client.goal(mode).runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
			assertThat(execution.awaitTermination(Duration.ofSeconds(3)).cleanupConfirmed()).isTrue();
		}
		assertThat(Files.readAllLines(root.resolve("launches.jsonl"))).hasSize(1);
		assertDead(root);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cancelStopsExactChildWithoutReplay(boolean interrupt) throws Exception {
		try (var client = client().build()) {
			var execution = client.goal("wait").runAsync();
			awaitFile(root.resolve("waiting"));
			assertThat(execution.cancel(interrupt)).isTrue();
			assertThatThrownBy(execution::result).isInstanceOf(CancellationException.class);
			assertThat(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
		}
		assertThat(Files.readAllLines(root.resolve("launches.jsonl"))).hasSize(1);
		assertDead(root);
	}

	@Test
	void deadlineTerminatesChild() throws Exception {
		try (var client = client().build()) {
			var execution = client.goal("wait").executionTimeout(Duration.ofMillis(300)).runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
			assertThat(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
		}
		assertDead(root);
	}

	@Test
	void stopsBeforeDispatchAndRejectsRetainedAndReplay() throws Exception {
		var provider = provider();
		assertThat(provider.capabilities().retention().supported()).isFalse();
		assertThatThrownBy(() -> provider.open(options(), true)).isInstanceOf(UnsupportedOperationException.class);
		try (var connection = provider.open(options())) {
			var turn = connection.prepare("E");
			turn.stop(false);
			assertThatThrownBy(() -> turn.call(new AgentTaskRequest("never", root, options())))
				.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> connection.prepare("again")).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> turn.call(new AgentTaskRequest("replay", root, options())))
				.isInstanceOf(IllegalStateException.class);
		}
		assertThat(root.resolve("pid")).doesNotExist();
	}

	@Test
	void captureWriteFailureCannotReverseResult() throws Exception {
		Files.writeString(root.resolve("captures"), "not a directory");
		try (var client = client().executionRecorder(recorder(new CompletableFuture<>(), new CountDownLatch(0)))
			.build()) {
			var execution = client.goal("success").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			var decision = execution.decision().orElseThrow();
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (!(execution.recording().delivery() instanceof TerminalDelivery.Failed) && System.nanoTime() < end)
				Thread.sleep(5);
			assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Failed.class);
			assertThat(execution.decision()).contains(decision);
		}
	}

	@Test
	void unsupportedPortableControlsFailBeforeLaunch() {
		var limited = DefaultAgentOptions.builder().maxTurns(1).build();
		assertThatThrownBy(() -> provider().open(limited)).isInstanceOf(UnsupportedOperationException.class);
		assertThat(root.resolve("pid")).doesNotExist();
	}

	private static ExecutionRecorder recorder(CompletableFuture<CodexAcquisition> evidence, CountDownLatch release) {
		return new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				evidence.complete((CodexAcquisition) values.get("codexAcquisition"));
				return () -> {
					try {
						release.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
					return "ack";
				};
			}
		};
	}

	static void awaitFile(Path file) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!Files.exists(file) && System.nanoTime() < until)
			Thread.sleep(5);
		assertThat(file).exists();
	}

	static void assertDead(Path cwd) throws Exception {
		assertThat(ProcessHandle.of(Long.parseLong(Files.readString(cwd.resolve("pid"))))
			.map(ProcessHandle::isAlive)
			.orElse(false)).isFalse();
	}

}
