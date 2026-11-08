/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionException;
import io.github.markpollack.agents.client.CaptureRecordReference;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.ExecutionRecorder;
import io.github.markpollack.agents.client.FailureCode;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;
import io.github.markpollack.agents.client.TerminalDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A caller holding only the execution handle of a failed one-shot turn can reach the
 * sealed capture: the receipt names the manifest that carries argv, exit status, observed
 * processes and the stdout/stderr sources. The reference is diagnostic; it does not
 * depend on, or change, the recorder's native qualification.
 */
@Timeout(30)
class CodexFailedExecutionRecordTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path root;

	@BeforeEach
	void setup() throws Exception {
		for (String name : List.of("codex-child.py", "codex-native-template.jsonl"))
			try (var in = getClass().getResourceAsStream("/" + name)) {
				Files.copy(in, this.root.resolve(name));
			}
		Files.writeString(this.root.resolve("codex-nonzero.sh"),
				"#!/bin/sh\ncat >/dev/null\nprintf '{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}\\n'\n"
						+ "echo 'known-stderr-7f3a' >&2\nexit 3\n");
		for (String name : List.of("codex-child.py", "codex-nonzero.sh"))
			Files.setPosixFilePermissions(this.root.resolve(name), PosixFilePermissions.fromString("rwx------"));
	}

	private AgentClient client(String executable, ExecutionRecorder recorder) {
		var provider = CodexClientProvider.builder()
			.codexPath(executable)
			.workingDirectory(this.root)
			.nativeSessionsDirectory(this.root.resolve("sessions"))
			.acquisitionDirectory(this.root.resolve("captures"), "0.166.0")
			.skipGitCheck(true)
			.build();
		var options = DefaultAgentOptions.builder()
			.workingDirectory(this.root.toString())
			.timeout(Duration.ofSeconds(8))
			.environmentVariables(Map.of("FIXTURE_SESSIONS", this.root.resolve("sessions").toString()))
			.model("fixture-model")
			.build();
		return AgentClient.builder(provider)
			.defaultOptions(options)
			.defaultWorkingDirectory(this.root)
			.executionRecorder(recorder)
			.build();
	}

	/**
	 * The documented caller sequence: failed result, bounded termination, settled
	 * recording.
	 */
	private static Settled settle(AgentExecution execution) throws Exception {
		var failure = catchThrowableOfType(execution::result, AgentExecutionException.class);
		assertThat(failure).isNotNull();
		var termination = execution.awaitTermination(Duration.ofSeconds(10));
		long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed
				|| execution.recording().delivery() instanceof TerminalDelivery.Failed) && System.nanoTime() < end)
			Thread.sleep(5);
		assertThat(termination.cleanupConfirmed()).isTrue();
		return new Settled(failure, execution.recording().evidence().fullRecord());
	}

	private record Settled(AgentExecutionException failure, Optional<CaptureRecordReference> record) {

		JsonNode manifest(String executionId) throws Exception {
			var reference = this.record.orElseThrow();
			Path file = Path.of(reference.uri());
			assertThat(reference.format()).isEqualTo("codex-cli-0.166.0-exec-stdio-rollout");
			assertThat(reference.sha256()).contains(NativeGeneration.sha256(file));
			var manifest = JSON.readTree(file.toFile());
			assertThat(manifest.path("run").path("run_uid").asText()).isEqualTo(executionId);
			return manifest;
		}

		String source(JsonNode manifest, String role) throws Exception {
			Path dir = Path.of(this.record.orElseThrow().uri()).getParent();
			return Files.readString(dir.resolve(role + ".bin"), java.nio.charset.StandardCharsets.ISO_8859_1);
		}

	}

	@ParameterizedTest
	@ValueSource(strings = { "eof", "malformed" })
	void failedStdoutTurnNamesItsSealedCapture(String mode) throws Exception {
		try (var client = client(this.root.resolve("codex-child.py").toString(), new Probe(false))) {
			var execution = client.goal(mode).runAsync();
			var settled = settle(execution);
			assertThat(settled.failure().failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
			assertThat(settled.failure().failure().causes()).isNotEmpty();
			var manifest = settled.manifest(execution.executionId());
			assertThat(manifest.path("process").path("exit_code").asInt()).isZero();
			assertThat(manifest.path("process").path("observed_pids")).isNotEmpty();
			assertThat(settled.source(manifest, "stderr")).contains("original-stderr-");
		}
	}

	@Test
	void nonzeroExitWithoutTerminalNamesExitAndStderr() throws Exception {
		try (var client = client(this.root.resolve("codex-nonzero.sh").toString(), new Probe(false))) {
			var execution = client.goal("nonzero").runAsync();
			var settled = settle(execution);
			assertThat(settled.failure().failure().causes())
				.containsExactly("java.io.IOException: Missing Codex identity or terminal event");
			var manifest = settled.manifest(execution.executionId());
			assertThat(manifest.path("process").path("exit_code").asInt()).isEqualTo(3);
			assertThat(settled.source(manifest, "stderr")).isEqualTo("known-stderr-7f3a\n");
		}
	}

	@Test
	void launchFailureNamesACaptureWithNoProcess() throws Exception {
		try (var client = client(this.root.resolve("absent/codex").toString(), new Probe(false))) {
			var execution = client.goal("launch").runAsync();
			var settled = settle(execution);
			assertThat(settled.failure().failure().causes())
				.anySatisfy(cause -> assertThat(cause).startsWith("org.zeroturnaround.exec.ProcessInitException"));
			var manifest = settled.manifest(execution.executionId());
			assertThat(manifest.path("process").path("exit_code").isNull()).isTrue();
			assertThat(manifest.path("process").path("observed_pids")).isEmpty();
		}
	}

	/**
	 * A recorder that refuses after sealing still leaves the reference and the original
	 * failure.
	 */
	@Test
	void recorderFailureKeepsReferenceAndOriginalFailure() throws Exception {
		try (var client = client(this.root.resolve("codex-nonzero.sh").toString(), new Probe(true))) {
			var execution = client.goal("nonzero").runAsync();
			var settled = settle(execution);
			assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Failed.class);
			assertThat(settled.failure().failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
			assertThat(settled.failure().failure().causes())
				.containsExactly("java.io.IOException: Missing Codex identity or terminal event");
			assertThat(settled.manifest(execution.executionId()).path("process").path("exit_code").asInt())
				.isEqualTo(3);
		}
	}

	private record Probe(boolean refuse) implements ExecutionRecorder {

		@Override
		public Supplier<String> prepare(ExecutionDecision decision) {
			return () -> "probe-" + decision.executionId();
		}

		@Override
		public boolean takesNativeGeneration() {
			return true;
		}

		@Override
		public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
			if (this.refuse)
				throw new IllegalStateException("recorder refused");
			return new Prepared(prepare(decision), new NativeRecording(NativeRecording.Outcome.REJECTED,
					generation.map(NativeGeneration::acquisitionProfile), "probe", "probe"));
		}

	}

}
