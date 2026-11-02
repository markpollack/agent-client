/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.zeroturnaround.exec.ProcessExecutor;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;
import static org.assertj.core.api.Assertions.*;

/** Real subprocess policy contract, deliberately separate from native enforcement. */
@Timeout(30)
class ClaudeClientHookTest {

	@TempDir
	Path directory;

	private final AtomicInteger callbacks = new AtomicInteger();

	private Path fixture() throws Exception {
		Path script = directory.resolve("child.py");
		if (!Files.exists(script)) {
			try (var input = getClass().getResourceAsStream("/retained-child.py")) {
				Files.copy(input, script);
			}
			Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		}
		return script;
	}

	private AgentClient client(String mode, boolean recordingFails) throws Exception {
		var provider = ClaudeClientProvider.builder()
			.workingDirectory(directory)
			.claudePath(fixture().toString())
			.tools(List.of("Write"))
			.acquisitionDirectory(directory.resolve("capture"), "2.1.316")
			.preToolUse(".*", input -> {
				callbacks.incrementAndGet();
				if (mode.equals("throw"))
					throw new IllegalStateException("application hook failed");
				if (mode.equals("null"))
					return null;
				return HookOutput.builder()
					.hookSpecificOutput(mode.equals("allow") ? HookOutput.HookSpecificOutput.preToolUseAllow()
							: HookOutput.HookSpecificOutput.preToolUseDeny("fixture policy"))
					.build();
			})
			.build();
		ExecutionRecorder recorder = decision -> () -> {
			if (recordingFails)
				throw new IllegalStateException("independent recording failure");
			return "fixture-recording-ack";
		};
		return AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(8)).executionRecorder(recorder).build();
	}

	@ParameterizedTest
	@ValueSource(strings = { "allow", "deny" })
	void peerAppliesNestedPermissionAndOmitsAbsentControls(String mode) throws Exception {
		try (var client = client(mode, false)) {
			var execution = client.goal("hook-" + mode).runAsync();
			assertThat(execution.result().getResult()).isEqualTo(mode.toUpperCase());
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Success.class);
			settled(execution, false);
		}
		assertThat(callbacks).hasValue(1);
		assertThat(Files.exists(directory.resolve("operation"))).isEqualTo(mode.equals("allow"));
		var mapper = new ObjectMapper();
		var response = Files.readAllLines(directory.resolve("wire.jsonl")).stream().map(line -> {
			try {
				return mapper.readTree(line);
			}
			catch (Exception ex) {
				throw new AssertionError(ex);
			}
		})
			.filter(node -> node.path("type").asText().equals("control_response"))
			.findFirst()
			.orElseThrow()
			.path("response")
			.path("response");
		assertThat(response.has("continue")).isFalse();
		assertThat(response.path("hookSpecificOutput").path("permissionDecision").asText()).isEqualTo(mode);
		assertOneStoppedProcess();
	}

	@ParameterizedTest
	@ValueSource(strings = { "throw", "null" })
	void sdkCallbackFallbackBlocksPeerOperationButProviderCanFinish(String mode) throws Exception {
		try (var client = client(mode, false)) {
			var execution = client.goal("hook-" + mode).runAsync();
			assertThat(execution.result().getResult()).isEqualTo("DENY");
			settled(execution, false);
		}
		assertThat(callbacks).hasValue(1);
		assertThat(directory.resolve("operation")).doesNotExist();
		assertThat(Files.readString(directory.resolve("wire.jsonl"))).contains("Hook execution failed");
		assertOneStoppedProcess();
	}

	@Test
	void explicitPeerRejectionReturnsStoppedWithIndependentRecordingFailure() throws Exception {
		try (var client = client("deny", true)) {
			var execution = client.goal("hook-reject").runAsync();
			assertThat(execution.result().isSuccessful()).isFalse();
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Stopped.class);
			settled(execution, true);
		}
		assertThat(directory.resolve("operation")).doesNotExist();
		assertOneStoppedProcess();
	}

	@Test
	void peerClosesAfterRejectionWithoutTerminalResult() throws Exception {
		try (var client = client("deny", true)) {
			var execution = client.goal("hook-eof").runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
			var outcome = (ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome();
			assertThat(outcome.failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
			settled(execution, true);
		}
		assertOneStoppedProcess();
	}

	@Test
	void stderrOnlyRejectionIsNotStructuredPolicyFailure() throws Exception {
		try (var client = client("deny", true)) {
			var execution = client.goal("hook-reject-success").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("PEER_REPORTED_SUCCESS_AFTER_REJECTION");
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Success.class);
			settled(execution, true);
		}
		assertThat(Files.readString(directory.resolve("operation"))).contains("despite hook rejection");
		assertThat(Files.readString(directory.resolve("hook-observation.json"))).contains("\"valid\": false");
		assertOneStoppedProcess();
	}

	@Test
	void cancelAfterHookKeepsCancellationAndRecordingFailureSeparate() throws Exception {
		try (var client = client("deny", true)) {
			var execution = client.goal("hook-cancel").runAsync();
			await(() -> Files.exists(directory.resolve("waiting")));
			assertThat(execution.cancel(false)).isTrue();
			assertThatThrownBy(execution::result).isInstanceOf(CancellationException.class);
			settled(execution, true);
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Cancelled.class);
		}
		assertThat(directory.resolve("operation")).doesNotExist();
		assertOneStoppedProcess();
	}

	@Test
	void malformedNullFlattenedAndContinueOnlyPoliciesAreRejected() throws Exception {
		var result = new ProcessExecutor().command(fixture().toString(), "--policy-self-test")
			.readOutput(true)
			.timeout(5, TimeUnit.SECONDS)
			.execute();
		assertThat(result.getExitValue()).isZero();
		assertThat(result.outputUTF8()).contains("POLICY_NEGATIVES_PASS=8");
	}

	private void settled(AgentExecution execution, boolean recordingFails) throws Exception {
		assertThat(execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
		await(() -> execution.recording().delivery() instanceof TerminalDelivery.Completed
				|| execution.recording().delivery() instanceof TerminalDelivery.Failed);
		assertThat(execution.recording().delivery())
			.isInstanceOf(recordingFails ? TerminalDelivery.Failed.class : TerminalDelivery.Completed.class);
		assertThat(execution.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.INCOMPLETE);
	}

	private void await(java.util.function.BooleanSupplier condition) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < end)
			Thread.sleep(5);
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private void assertOneStoppedProcess() throws Exception {
		assertThat(Files.readAllLines(directory.resolve("launches.jsonl"))).hasSize(1);
		assertThat(Files.readAllLines(directory.resolve("prompts.jsonl"))).hasSize(1);
		try (var paths = Files.list(directory)) {
			for (var path : paths.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
				assertThat(ProcessHandle.of(Long.parseLong(Files.readString(path)))
					.map(ProcessHandle::isAlive)
					.orElse(false)).isFalse();
		}
	}

}
