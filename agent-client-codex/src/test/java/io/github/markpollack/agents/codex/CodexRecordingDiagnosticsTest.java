/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Secondary recording facts become observable after close without touching execution
 * results: the retained thread manifest's path, or the exact reason it was not written.
 * Every launched process selects its permissions explicitly on argv.
 */
@Timeout(60)
class CodexRecordingDiagnosticsTest {

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
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
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
			.build();
	}

	private static ExecutionRecorder recorder(BlockingQueue<CodexAcquisition> sealed) {
		return new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				sealed.add((CodexAcquisition) values.get("codexAcquisition"));
				return () -> "ack";
			}
		};
	}

	@Test
	void writtenThreadManifestIsReportedWithItsPathAfterClose() throws Exception {
		Path home = Files.createDirectories(root.resolve("written"));
		var sealed = new LinkedBlockingQueue<CodexAcquisition>();
		AgentSession session;
		try (var client = AgentClient.builder(provider(home).build())
			.defaultOptions(options(home))
			.defaultWorkingDirectory(home)
			.executionRecorder(recorder(sealed))
			.build()) {
			session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)));
			assertThat(session.goal("first").runAsync().result(Duration.ofSeconds(5)).getResult())
				.isEqualTo("answer:first");
			assertThat(session.goal("second").runAsync().result(Duration.ofSeconds(5)).getResult())
				.isEqualTo("answer:second");
			assertThat(session.inspect().diagnostics()).isEmpty();
			session.close();
		}
		var diagnostics = session.inspect().diagnostics();
		assertThat(session.inspect().state()).isEqualTo(SessionState.CLOSED);
		assertThat(diagnostics).hasSize(1);
		assertThat(diagnostics.get(0)).startsWith("codex thread manifest written: ");
		Path manifest = Path.of(diagnostics.get(0).substring("codex thread manifest written: ".length()));
		assertThat(manifest).exists();
		assertThat(JSON.readTree(manifest.toFile()).path("turns")).hasSize(2);
	}

	@Test
	void unwritableCaptureRootAtCloseKeepsBothResultsAndReportsTheManifestFailure() throws Exception {
		Path home = Files.createDirectories(root.resolve("unwritable"));
		Path captures = home.resolve("captures");
		var sealed = new LinkedBlockingQueue<CodexAcquisition>();
		AgentSession session;
		String first;
		String second;
		try (var client = AgentClient.builder(provider(home).build())
			.defaultOptions(options(home))
			.defaultWorkingDirectory(home)
			.executionRecorder(recorder(sealed))
			.build()) {
			session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)));
			first = session.goal("first").runAsync().result(Duration.ofSeconds(5)).getResult();
			second = session.goal("second").runAsync().result(Duration.ofSeconds(5)).getResult();
			assertThat(sealed.poll(5, TimeUnit.SECONDS)).isNotNull();
			assertThat(sealed.poll(5, TimeUnit.SECONDS)).isNotNull();
			// Both per-turn acquisitions are sealed; only the close-time thread manifest
			// can fail now.
			Files.setPosixFilePermissions(captures, PosixFilePermissions.fromString("r-x------"));
			try {
				session.close();
			}
			finally {
				Files.setPosixFilePermissions(captures, PosixFilePermissions.fromString("rwx------"));
			}
		}
		assertThat(first).isEqualTo("answer:first");
		assertThat(second).isEqualTo("answer:second");
		assertThat(session.inspect().state()).isEqualTo(SessionState.CLOSED);
		assertThat(session.inspect().diagnostics()).hasSize(1);
		assertThat(session.inspect().diagnostics().get(0)).startsWith("codex thread manifest not written: ")
			.contains("captures");
		try (var dirs = Files.list(captures)) {
			assertThat(dirs.filter(p -> p.getFileName().toString().startsWith("codex-thread-"))).isEmpty();
		}
	}

	@Test
	void providerDefaultsSelectPermissionsExplicitlyOnEveryProcess() throws Exception {
		Path home = Files.createDirectories(root.resolve("argv"));
		try (var client = AgentClient.builder(provider(home).build())
			.defaultOptions(options(home))
			.defaultWorkingDirectory(home)
			.build(); var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
			session.goal("first").runAsync().result(Duration.ofSeconds(5));
			session.goal("second").runAsync().result(Duration.ofSeconds(5));
		}
		var launches = Files.readAllLines(home.resolve("launches.jsonl"));
		assertThat(launches).hasSize(2);
		for (String launch : launches) {
			List<String> argv = JSON.readValue(launch, new TypeReference<List<String>>() {
			});
			int exec = argv.indexOf("exec");
			assertThat(argv.subList(0, exec)).as("globals before exec: " + argv)
				.contains("--no-daemon")
				.containsSequence("--ask-for-approval", "never")
				.containsSequence("--sandbox", "read-only");
			int subcommand = argv.contains("resume") ? argv.indexOf("resume") : exec;
			assertThat(argv.get(subcommand + 1)).isEqualTo("--ignore-user-config");
			assertThat(argv).doesNotContain("--dangerously-bypass-approvals-and-sandbox", "--full-auto");
		}
	}

}
