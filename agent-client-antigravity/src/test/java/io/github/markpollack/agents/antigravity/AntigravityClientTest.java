/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

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
class AntigravityClientTest {

	@TempDir
	Path root;

	Path script;

	@BeforeEach
	void setup() throws Exception {
		this.script = root.resolve("child.py");
		try (var in = getClass().getResourceAsStream("/antigravity-child.py")) {
			Files.copy(in, script);
		}
		Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
	}

	private AntigravityClientProvider provider() {
		return AntigravityClientProvider.builder()
			.executable(script.toString())
			.workingDirectory(root)
			.acquisitionDirectory(root.resolve("captures"), "1.4.6")
			.build();
	}

	private DefaultAgentOptions options() {
		return DefaultAgentOptions.builder()
			.workingDirectory(root.toString())
			.timeout(Duration.ofSeconds(8))
			.environmentVariables(Map.of("FIXTURE_MARKER", "exact-environment"))
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
		var evidence = new CompletableFuture<AntigravityAcquisition>();
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
			assertThat(decoded.path("sessionId").asText()).isEqualTo(d.sessionId());
			assertThat(decoded.path("decisionId").asText()).isEqualTo(d.decisionId());
			assertThat(decoded.path("nativeVersionObserved").asBoolean()).isFalse();
			assertThat(Files.readAllBytes(a.directory().resolve("prompt-argument.bin")))
				.isEqualTo(Files.readAllBytes(cwd.resolve("prompt.bin")));
			assertThat(Files.readAllBytes(a.directory().resolve("stdin.bin"))).isEmpty();
			assertThat(Files.readAllBytes(a.directory().resolve("stderr.bin"))).contains((byte) 0xff);
			assertThat(Files.readString(cwd.resolve("launches.jsonl"))).contains("--output-format", "stream-json",
					"--model", "fixture-model", cwd.toString(), "--add-dir", "--print-timeout", "--print");
			assertThat(Files.readString(cwd.resolve("environment"))).isEqualTo("exact-environment");
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
	@ValueSource(strings = { "eof", "duplicate", "malformed", "wrong", "missing", "incomplete", "overflow",
			"stderr-overflow" })
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

	@Test
	void nativeStatusAndDerivedRecoveryAndSoftDenialStayDistinct() throws Exception {
		try (var client = client().build()) {
			var recovered = client.goal("recover").runAsync().result();
			assertThat(recovered.isSuccessful()).isTrue();
			assertThat(recovered.getMetadata()).containsEntry("nativeReason", "ERROR");
			assertThat(client.goal("denied").runAsync().result().isSuccessful()).isFalse();
			assertThat(client.goal("failed").runAsync().result().isSuccessful()).isFalse();
		}
	}

	@Test
	void directBoundaryRejectsWrongDirectoryAndUnknownOptions() throws Exception {
		var provider = provider();
		assertThatThrownBy(() -> provider.open(DefaultAgentOptions.builder().timeout(Duration.ZERO).build()))
			.isInstanceOf(IllegalArgumentException.class);
		try (var connection = provider.open(options())) {
			assertThat(connection.providerReference()).isEmpty();
			var turn = connection.prepare("E");
			assertThat(turn.captureAcquisition()).isEqualTo(CaptureAcquisition.PENDING);
			assertThat(turn.recordingEvidence()).isEmpty();
			assertThatThrownBy(() -> turn.call(new AgentTaskRequest("x", root.resolve("wrong"), options())))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	private static ExecutionRecorder recorder(CompletableFuture<AntigravityAcquisition> evidence,
			CountDownLatch release) {
		return new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				evidence.complete((AntigravityAcquisition) values.get("antigravityAcquisition"));
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
