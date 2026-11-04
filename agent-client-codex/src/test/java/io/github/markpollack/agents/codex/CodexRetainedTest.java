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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import static org.assertj.core.api.Assertions.*;

/** Deterministic fixture: retained Codex turns resume the exact emitted thread. */
@Timeout(30)
class CodexRetainedTest {

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

	private Path home(String name) throws Exception {
		return Files.createDirectories(root.resolve(name));
	}

	private CodexClientProvider provider(Path home) {
		return CodexClientProvider.builder()
			.codexPath(script.toString())
			.workingDirectory(home)
			.nativeSessionsDirectory(home.resolve("sessions"))
			.acquisitionDirectory(home.resolve("captures"), "0.166.0")
			.skipGitCheck(true)
			.build();
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

	private AgentClient.Builder client(Path home) {
		return AgentClient.builder(provider(home)).defaultOptions(options(home)).defaultWorkingDirectory(home);
	}

	@Test
	void secondTurnResumesExactEmittedThreadWithContinuousNativePrefix() throws Exception {
		Path home = home("a");
		var acquisitions = new LinkedBlockingQueue<CodexAcquisition>();
		try (var client = client(home).executionRecorder(recorder(acquisitions)).build();
				var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
			assertThat(session.capabilities().retention().supported()).isTrue();
			var first = session.goal("first").runAsync();
			assertThat(first.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:first");
			String thread = session.providerReference().orElseThrow().nativeId();
			assertThat(thread).isNotBlank();
			var a1 = acquisitions.poll(3, TimeUnit.SECONDS);
			assertThat(a1.complete()).as(a1.detail()).isTrue();
			var m1 = manifest(a1, first.decision().orElseThrow());
			assertThat(m1.path("process").path("fresh_session").asBoolean()).isTrue();
			assertThat(m1.path("turns").get(0).path("native_prefix_before").path("state").asText()).isEqualTo("ABSENT");
			assertThat(m1.path("turns").get(0).path("native_records").path("first_line").asLong()).isZero();

			assertThat(first.cancel(true)).as("stale cancel after completion").isFalse();
			var second = session.goal("second").runAsync();
			var response = second.result(Duration.ofSeconds(5));
			assertThat(response.getResult()).isEqualTo("answer:second");
			assertThat((Object) response.getMetadata().get("resumedThread")).isEqualTo(thread);
			assertThat((Object) response.getMetadata().get("identitySource")).isEqualTo("stdout");
			assertThat((Object) response.getMetadata().get("turnIndex")).isEqualTo(1);
			assertThat(second.decision().orElseThrow().connectionId())
				.isNotEqualTo(first.decision().orElseThrow().connectionId());
			assertThat(session.providerReference().orElseThrow().nativeId()).isEqualTo(thread);

			var launches = Files.readAllLines(home.resolve("launches.jsonl"));
			assertThat(launches).hasSize(2);
			assertThat(launches.get(0)).doesNotContain("resume");
			assertThat(launches.get(1)).contains("\"exec\", \"resume\", \"--ignore-user-config\"",
					"\"--json\", \"" + thread + "\", \"--\", \"-\"", "--no-daemon", "fixture-model",
					"--skip-git-repo-check");
			assertThat(Files.readString(home.resolve("prompt.bin"))).isEqualTo("second");

			var a2 = acquisitions.poll(3, TimeUnit.SECONDS);
			assertThat(a2.complete()).as(a2.detail()).isTrue();
			assertThat(a2.nativeId()).contains(thread);
			var m2 = manifest(a2, second.decision().orElseThrow());
			var t2 = m2.path("turns").get(0);
			assertThat(m2.path("process").path("fresh_session").asBoolean()).isFalse();
			assertThat(m2.path("process").path("native_path_preexisting").asBoolean()).isTrue();
			assertThat(m2.path("run").path("invocation_index").asInt()).isEqualTo(1);
			byte[] native1 = Files.readAllBytes(a1.directory().resolve("native.bin"));
			byte[] native2 = Files.readAllBytes(a2.directory().resolve("native.bin"));
			assertThat(Arrays.copyOf(native2, native1.length)).isEqualTo(native1);
			assertThat(t2.path("native_prefix_before").path("bytes").asLong()).isEqualTo(native1.length);
			assertThat(t2.path("native_prefix_after").path("bytes").asLong()).isEqualTo(native2.length);
			long lines1 = m1.path("turns").get(0).path("native_records").path("last_line").asLong() + 1;
			assertThat(t2.path("native_records").path("first_line").asLong()).isEqualTo(lines1);
			assertThat(t2.path("native_turn_uid").asText())
				.isNotEqualTo(m1.path("turns").get(0).path("native_turn_uid").asText());
			assertThat(t2.path("cumulative_usage").path("total_tokens").asInt()).isEqualTo(30);
			assertThat(m1.path("turns").get(0).path("cumulative_usage").path("total_tokens").asInt()).isEqualTo(15);
			assertThat(t2.path("wire_usage").path("input_tokens").asInt()).isEqualTo(11);
			assertThat(new String(native2, java.nio.charset.StandardCharsets.UTF_8)).containsOnlyOnce("session_meta");
			assertThat(second.awaitTermination(Duration.ofSeconds(3)).cleanupConfirmed()).isTrue();
		}
		assertDead(home);
	}

	@Test
	void twoIndependentSessionsKeepSeparateThreadsAndStopsIsolated() throws Exception {
		Path a = home("a"), b = home("b");
		try (var client = client(a).build();
				var one = client.sessions().create(s -> s.workingDirectory(a).options(options(a)));
				var two = client.sessions().create(s -> s.workingDirectory(b).options(options(b)))) {
			one.goal("one").runAsync().result(Duration.ofSeconds(5));
			two.goal("two").workingDirectory(b).runAsync().result(Duration.ofSeconds(5));
			String ta = one.providerReference().orElseThrow().nativeId();
			String tb = two.providerReference().orElseThrow().nativeId();
			assertThat(ta).isNotEqualTo(tb);
			var waiting = one.goal("wait").runAsync();
			CodexClientTest.awaitFile(a.resolve("waiting"));
			var follow = two.goal("two:again").workingDirectory(b).runAsync();
			assertThat((Object) follow.result(Duration.ofSeconds(5)).getMetadata().get("resumedThread")).isEqualTo(tb);
			assertThat(waiting.cancel(false)).isTrue();
			assertThatThrownBy(waiting::result).isInstanceOf(CancellationException.class);
			assertThat(waiting.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			assertThat((Object) two.goal("two:third")
				.workingDirectory(b)
				.runAsync()
				.result(Duration.ofSeconds(5))
				.getMetadata()
				.get("turnIndex")).isEqualTo(2);
			assertThat(two.providerReference().orElseThrow().nativeId()).isEqualTo(tb);
			assertThat(Files.readAllLines(b.resolve("launches.jsonl"))).hasSize(3);
			assertThat(one.inspect().state()).isEqualTo(SessionState.READY);
			var quarantined = one.goal("after-cancel").runAsync();
			var failure = catchThrowableOfType(quarantined::result, AgentExecutionException.class);
			assertThat(failure.failure().code()).isEqualTo(FailureCode.SETUP_FAILED);
			assertThat(failure).hasStackTraceContaining("quarantined");
			assertThat(Files.readAllLines(a.resolve("launches.jsonl"))).hasSize(2);
		}
		assertDead(a);
		assertDead(b);
	}

	@ParameterizedTest
	@ValueSource(strings = { "cancel-false", "cancel-true", "deadline", "failed", "wrong", "eof" })
	void incompleteTurnQuarantinesThreadUntilClose(String mode) throws Exception {
		Path home = home("q");
		try (var client = client(home).build();
				var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
			var first = session.goal("first").runAsync();
			assertThat(first.result(Duration.ofSeconds(5)).isSuccessful()).isTrue();
			var spec = session.goal(mode.startsWith("cancel") || mode.equals("deadline") ? "wait" : mode);
			if (mode.equals("deadline"))
				spec.executionTimeout(Duration.ofMillis(300));
			var second = spec.runAsync();
			if (mode.startsWith("cancel")) {
				CodexClientTest.awaitFile(home.resolve("waiting"));
				assertThat(second.cancel(mode.equals("cancel-true"))).isTrue();
				assertThatThrownBy(second::result).isInstanceOf(CancellationException.class);
			}
			else if (mode.equals("failed")) {
				assertThat(second.result(Duration.ofSeconds(5)).isSuccessful()).isFalse();
			}
			else {
				assertThatThrownBy(second::result).isInstanceOf(AgentExecutionException.class);
			}
			assertThat(second.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			var third = session.goal("third").runAsync();
			var failure = catchThrowableOfType(third::result, AgentExecutionException.class);
			assertThat(failure.failure().code()).isEqualTo(FailureCode.SETUP_FAILED);
			assertThat(failure).hasStackTraceContaining("quarantined");
			assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(2);
		}
		assertDead(home);
	}

	@Test
	void resumedTurnWithoutEmittedIdentityUsesExactResumeArgument() throws Exception {
		Path home = home("n");
		try (var client = client(home).build();
				var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
			var first = session.goal("first").runAsync();
			assertThat(first.result(Duration.ofSeconds(5)).isSuccessful()).isTrue();
			var response = session.goal("noid").runAsync().result(Duration.ofSeconds(5));
			assertThat(response.getResult()).isEqualTo("answer:noid");
			assertThat((Object) response.getMetadata().get("identitySource")).isEqualTo("resume-argv");
			assertThat((Object) response.getMetadata().get("resumedThread"))
				.isEqualTo(session.providerReference().orElseThrow().nativeId());
		}
	}

	@Test
	void oneShotStillRunsExactlyOneFreshProcess() throws Exception {
		Path home = home("o");
		try (var client = client(home).build()) {
			var execution = client.goal("solo").runAsync();
			assertThat((Object) execution.result(Duration.ofSeconds(5)).getMetadata().get("turnIndex")).isEqualTo(0);
			assertThat(execution.awaitTermination(Duration.ofSeconds(3)).cleanupConfirmed()).isTrue();
		}
		assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(1);
		assertThat(Files.readString(home.resolve("launches.jsonl"))).doesNotContain("resume");
	}

	private static com.fasterxml.jackson.databind.JsonNode manifest(CodexAcquisition acquisition,
			ExecutionDecision decision) throws Exception {
		return JSON.readTree(Files.readAllBytes(acquisition.writeManifest(decision)));
	}

	private static ExecutionRecorder recorder(BlockingQueue<CodexAcquisition> evidence) {
		return new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				evidence.add((CodexAcquisition) values.get("codexAcquisition"));
				return () -> "ack";
			}
		};
	}

	private static void assertDead(Path home) throws Exception {
		CodexClientTest.assertDead(home);
	}

}
