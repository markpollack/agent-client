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
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.CaptureAcquisition;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** The typed one-shot generation Codex seals for a recorder that takes generations. */
@Timeout(25)
class CodexGenerationTest {

	@TempDir
	Path root;

	@BeforeEach
	void setup() throws Exception {
		for (String name : List.of("codex-child.py", "codex-native-template.jsonl"))
			try (var in = getClass().getResourceAsStream("/" + name)) {
				Files.copy(in, this.root.resolve(name));
			}
		Files.setPosixFilePermissions(this.root.resolve("codex-child.py"),
				PosixFilePermissions.fromString("rwx------"));
	}

	private AgentClient.Builder client(Path captures, GenerationProbe probe) {
		var provider = CodexClientProvider.builder()
			.codexPath(this.root.resolve("codex-child.py").toString())
			.workingDirectory(this.root)
			.nativeSessionsDirectory(this.root.resolve("sessions"))
			.acquisitionDirectory(captures, "0.166.0")
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
			.executionRecorder(probe);
	}

	@Test
	void oneShotSealsOneMemberWithTheDecisionBoundManifest() throws Exception {
		var probe = new GenerationProbe();
		try (var client = client(this.root.resolve("captures"), probe).build()) {
			var execution = client.goal("success").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("answer:success");
			var decision = execution.decision().orElseThrow();
			var generation = probe.sealed.get(5, TimeUnit.SECONDS).orElseThrow();
			assertThat(generation.acquisitionProfile()).isEqualTo("codex-cli-0.166.0-exec-stdio-rollout");
			assertThat(generation.members())
				.containsExactly(new NativeGeneration.Member(decision.executionId(), decision.decisionId()));
			assertThat(generation.sessionId()).isEqualTo(decision.sessionId());
			assertThat(generation.connectionId()).isEqualTo(decision.connectionId().orElseThrow());
			assertThat(generation.complete()).as(generation.detail()).isTrue();
			assertThat(NativeGeneration.sha256(generation.manifest())).isEqualTo(generation.manifestSha256());
			var manifest = new ObjectMapper().readTree(generation.manifest().toFile());
			assertThat(manifest.path("profile").asText()).isEqualTo(generation.acquisitionProfile());
			assertThat(manifest.path("turns").get(0).path("decision_uid").asText()).isEqualTo(decision.decisionId());
			assertThat(execution.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.COMPLETED);
		}
	}

	@Test
	void retainedTurnIsNotAOneShotGeneration() throws Exception {
		var probe = new GenerationProbe();
		try (var client = client(this.root.resolve("captures"), probe).build();
				var session = client.sessions().create(s -> s.workingDirectory(this.root))) {
			var execution = session.goal("success").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("answer:success");
			assertThat(probe.sealed.get(5, TimeUnit.SECONDS)).isEmpty();
		}
	}

	@Test
	void sealingFailureStillArchivesTheDecision() throws Exception {
		Path captures = Files.writeString(this.root.resolve("captures-file"), "not a directory");
		var probe = new GenerationProbe();
		try (var client = client(captures, probe).build()) {
			var execution = client.goal("success").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			assertThat(probe.sealed.get(5, TimeUnit.SECONDS)).isEmpty();
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (execution.recording().nativeRecording().isEmpty() && System.nanoTime() < end)
				Thread.sleep(5);
			var observed = execution.recording().nativeRecording().orElseThrow();
			assertThat(observed.outcome()).isEqualTo(NativeRecording.Outcome.NOT_CAPTURED);
			assertThat(observed.detail()).startsWith("Native sealing failed");
		}
	}

}
