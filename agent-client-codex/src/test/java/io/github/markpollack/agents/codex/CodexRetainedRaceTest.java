/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;
import io.github.markpollack.agents.model.AgentTaskRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * Owner-requested reproductions: delayed secondary recording must not change either
 * turn's original boundaries; a stop after process completion must honour the stated
 * quarantine policy; sandbox and working-directory settings must reach every replacement
 * process.
 */
@Timeout(60)
class CodexRetainedRaceTest {

	private static final ObjectMapper JSON = new ObjectMapper();

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

	private CodexClientProvider.Builder provider(Path home) {
		return CodexClientProvider.builder()
			.codexPath(script.toString())
			.workingDirectory(home)
			.nativeSessionsDirectory(home.resolve("sessions"))
			.acquisitionDirectory(home.resolve("captures"), "0.166.0")
			.skipGitCheck(true);
	}

	private DefaultAgentOptions options(Path home) {
		return DefaultAgentOptions.builder()
			.workingDirectory(home.toString())
			.timeout(Duration.ofSeconds(8))
			.environmentVariables(Map.of("FIXTURE_SESSIONS", home.resolve("sessions").toString()))
			.model("fixture-model")
			.effort("low")
			.build();
	}

	/**
	 * Both recorder-lane threads are held by two one-shot executions whose delivery blocks
	 * on a latch, so the retained session's acquisitions queue behind them. Turn two must
	 * start and finish before any of that recording happens; when the gate opens, each
	 * turn's native region and prefix continuity must still be its own.
	 */
	@Test
	void secondTurnStartsBeforeFirstTurnRecordingAndBoundariesStayCorrect() throws Exception {
		Path home = Files.createDirectories(root.resolve("delay"));
		var gate = new CountDownLatch(1);
		var blocked = new CountDownLatch(2);
		var sealed = new LinkedBlockingQueue<Map.Entry<String, CodexAcquisition>>();
		var recorder = new ExecutionRecorder() {
			@Override
			public Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				var acquisition = (CodexAcquisition) values.get("codexAcquisition");
				sealed.add(Map.entry(d.executionId(), acquisition));
				return () -> {
					blocked.countDown();
					try {
						gate.await(20, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					return "ack";
				};
			}
		};
		try (var client = AgentClient.builder(provider(home).build())
			.defaultOptions(options(home))
			.defaultWorkingDirectory(home)
			.executionRecorder(recorder)
			.build()) {
			// Hold both recorder threads with two one-shot executions.
			var hold1 = client.goal("hold1").runAsync();
			var hold2 = client.goal("hold2").runAsync();
			hold1.result(Duration.ofSeconds(5));
			hold2.result(Duration.ofSeconds(5));
			assertThat(blocked.await(10, TimeUnit.SECONDS)).as("both recorder threads blocked").isTrue();
			sealed.clear();
			String thread;
			byte[] native2;
			JsonNode t1, t2;
			try (var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
				var first = session.goal("first").runAsync();
				assertThat(first.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:first");
				thread = session.providerReference().orElseThrow().nativeId();
				// Turn two starts and completes while turn one's recording is still queued.
				var second = session.goal("second").runAsync();
				assertThat(second.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:second");
				assertThat(sealed).as("no retained acquisition sealed before the gate opens").isEmpty();
				assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(4);
				gate.countDown();
				var a1 = sealed.poll(10, TimeUnit.SECONDS);
				var a2 = sealed.poll(10, TimeUnit.SECONDS);
				assertThat(a1).isNotNull();
				assertThat(a2).isNotNull();
				if (!a1.getKey().equals(first.executionId())) {
					var swap = a1;
					a1 = a2;
					a2 = swap;
				}
				assertThat(a1.getKey()).isEqualTo(first.executionId());
				assertThat(a2.getKey()).isEqualTo(second.executionId());
				assertThat(a1.getValue().complete()).as("turn 1: " + a1.getValue().detail()).isTrue();
				assertThat(a2.getValue().complete()).as("turn 2: " + a2.getValue().detail()).isTrue();
				var m1 = JSON.readTree(Files.readAllBytes(a1.getValue().writeManifest(first.decision().orElseThrow())));
				var m2 = JSON.readTree(Files.readAllBytes(a2.getValue().writeManifest(second.decision().orElseThrow())));
				t1 = m1.path("turns").get(0);
				t2 = m2.path("turns").get(0);
				byte[] native1 = Files.readAllBytes(a1.getValue().directory().resolve("native.bin"));
				native2 = Files.readAllBytes(a2.getValue().directory().resolve("native.bin"));
				assertThat(new String(native1, java.nio.charset.StandardCharsets.UTF_8))
					.as("turn 1 snapshot holds only its own turn")
					.containsOnlyOnce("task_started");
				assertThat(Arrays.copyOf(native2, native1.length)).isEqualTo(native1);
				assertThat(t2.path("native_prefix_before").path("bytes").asLong()).isEqualTo(native1.length);
				assertThat(t2.path("native_records").path("first_line").asLong())
					.isEqualTo(t1.path("native_records").path("last_line").asLong() + 1);
				assertThat(t1.path("native_turn_uid").asText()).isNotEqualTo(t2.path("native_turn_uid").asText());
				assertThat(a2.getValue().nativeId()).contains(thread);
				assertThat(second.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			}
			// Thread-level manifest written at close: one final transcript, two turns, partitioned locators.
			Path threadManifest;
			try (var stream = Files.list(home.resolve("captures"))) {
				threadManifest = stream.filter(p -> p.getFileName().toString().startsWith("codex-thread-"))
					.map(p -> p.resolve("codex-thread.json"))
					.findFirst()
					.orElseThrow();
			}
			var manifest = JSON.readTree(Files.readAllBytes(threadManifest));
			assertThat(manifest.path("kind").asText()).isEqualTo("journal-codex-retained-generation/1");
			assertThat(manifest.path("native_session_id").asText()).isEqualTo(thread);
			assertThat(manifest.path("cli_version").asText()).isEqualTo("0.166.0");
			assertThat(manifest.path("overflow").asBoolean()).isFalse();
			var transcript = manifest.path("transcript");
			byte[] finalBytes = Files.readAllBytes(threadManifest.resolveSibling("transcript.bin"));
			assertThat(transcript.path("sha256").asText()).isEqualTo(CodexNativeSource.sha256(finalBytes));
			assertThat(transcript.path("final_equals_last_sealed_prefix").asBoolean()).isTrue();
			assertThat(finalBytes).isEqualTo(native2);
			var turns = manifest.path("turns");
			assertThat(turns).hasSize(2);
			assertThat(turns.get(0).path("native_turn_uid").asText()).isEqualTo(t1.path("native_turn_uid").asText());
			assertThat(turns.get(1).path("native_turn_uid").asText()).isEqualTo(t2.path("native_turn_uid").asText());
			assertThat(turns.get(1).path("resume_thread_id").asText()).isEqualTo(thread);
			// Locators partition the whole transcript: shared session_meta, then turn 1, then turn 2.
			long covered = 0;
			var shared = turns.get(0).path("native_records").path("shared");
			assertThat(shared).hasSize(1);
			assertThat(shared.get(0).path("start").asLong()).isZero();
			covered = shared.get(0).path("end").asLong();
			for (var turn : turns)
				for (var record : turn.path("native_records").path("records")) {
					assertThat(record.path("start").asLong()).isEqualTo(covered);
					assertThat(record.path("source").asText()).isEqualTo("TRANSCRIPT");
					long end = record.path("end").asLong();
					assertThat(CodexNativeSource.sha256(Arrays.copyOfRange(finalBytes, (int) covered, (int) end)))
						.isEqualTo(record.path("sha256").asText());
					covered = end;
				}
			assertThat(covered).isEqualTo(finalBytes.length);
			// Usage identity: thread(2) - thread(1) == turn(2) on the fixture, never summed by Client.
			JsonNode u1 = turns.get(0).path("thread_token_usage"), u2 = turns.get(1).path("thread_token_usage");
			var w2 = turns.get(1).path("turn_token_usage");
			assertThat(u2.path("total_tokens").asInt() - u1.path("total_tokens").asInt())
				.isEqualTo(w2.path("total_tokens").asInt());
			assertThat(turns.get(1).path("total_token_usage").path("total_tokens").asInt()).isEqualTo(30);
			assertThat(turns.get(1).path("wire_usage").path("input_tokens").asInt()).isEqualTo(11);
		}
		CodexClientTest.assertDead(home);
	}

	/**
	 * Provider-level: the turn's process completed with a parsed turn.completed, then the
	 * Client lifecycle stops the turn (a cancel or deadline that won the race before the
	 * result was accepted). The stated policy quarantines any turn the caller did not see
	 * complete, so the thread must not be reusable.
	 */
	@Test
	void stopAfterCompletedProcessQuarantinesThread() throws Exception {
		Path home = Files.createDirectories(root.resolve("stop"));
		try (var connection = provider(home).build().open(options(home), true)) {
			var turn = connection.prepare("exec-1");
			var response = turn.call(new AgentTaskRequest("first", home, options(home)));
			assertThat(response.getResult().getText()).isEqualTo("answer:first");
			turn.stop(false);
			assertThatThrownBy(() -> connection.prepare("exec-2")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("quarantined");
		}
		assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(1);
	}

	@Test
	void sandboxAndWorkingDirectoryReachInitialAndResumedProcesses() throws Exception {
		Path home = Files.createDirectories(root.resolve("sandbox"));
		Path work = Files.createDirectories(root.resolve("work-dir"));
		var provider = provider(home).sandbox(SandboxMode.WORKSPACE_WRITE).build();
		try (var client = AgentClient.builder(provider).defaultOptions(options(work)).defaultWorkingDirectory(work).build();
				var session = client.sessions().create(s -> s.workingDirectory(work).options(options(work)))) {
			session.goal("first").runAsync().result(Duration.ofSeconds(5));
			session.goal("second").runAsync().result(Duration.ofSeconds(5));
		}
		var launches = Files.readAllLines(work.resolve("launches.jsonl"));
		assertThat(launches).hasSize(2);
		for (var launch : launches) {
			List<String> argv = JSON.readValue(launch, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
			});
			int exec = argv.indexOf("exec");
			assertThat(argv.subList(0, exec)).as("globals before exec: " + argv)
				.containsSequence("--sandbox", "workspace-write")
				.containsSequence("-C", work.toString())
				.containsSequence("--ask-for-approval", "never");
		}
		assertThat(launches.get(1)).contains("\"resume\"");
		// The child wrote its pid file into its actual working directory.
		assertThat(work.resolve("pid")).exists();
		assertThat(home.resolve("pid")).doesNotExist();
	}

}
