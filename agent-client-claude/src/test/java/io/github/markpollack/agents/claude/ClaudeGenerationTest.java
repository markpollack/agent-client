/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.NativeGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** The typed one-shot generation Claude seals: its manifest and digest, unchanged. */
@Timeout(30)
class ClaudeGenerationTest {

	@TempDir
	Path directory;

	private ClaudeClientProvider.Builder provider() throws Exception {
		Path script = this.directory.resolve("child.py");
		try (var source = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(source, script);
		}
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		return ClaudeClientProvider.builder()
			.workingDirectory(this.directory)
			.claudePath(script.toString())
			.acquisitionDirectory(this.directory.resolve("captures"), "2.1.316");
	}

	@Test
	void oneShotSealsTheProvidersManifestWithItsDigest() throws Exception {
		var probe = new GenerationProbe();
		try (var client = AgentClient.builder(provider().build()).executionRecorder(probe).build()) {
			var execution = client.goal("one shot").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			var decision = execution.decision().orElseThrow();
			var generation = probe.sealed.get(5, TimeUnit.SECONDS).orElseThrow();
			assertThat(generation.acquisitionProfile()).isEqualTo("claude-code-2.1.316-sdk-stdio-session-file");
			assertThat(generation.members())
				.containsExactly(new NativeGeneration.Member(decision.executionId(), decision.decisionId()));
			assertThat(NativeGeneration.sha256(generation.manifest())).isEqualTo(generation.manifestSha256());
			assertThat(new ObjectMapper().readTree(generation.manifest().toFile()).path("profile").asText())
				.isEqualTo(generation.acquisitionProfile());
			// The fixture writes no native transcript, so the capture is honestly
			// incomplete.
			assertThat(generation.complete()).isFalse();
			assertThat(generation.detail()).contains("incomplete");
		}
	}

	@Test
	void retainedTurnIsNotAOneShotGeneration() throws Exception {
		var probe = new GenerationProbe();
		try (var client = AgentClient.builder(provider().build()).executionRecorder(probe).build();
				var session = client.sessions().create(s -> {
				})) {
			assertThat(session.goal("first").runAsync().result().isSuccessful()).isTrue();
			assertThat(probe.sealed.get(5, TimeUnit.SECONDS)).isEmpty();
		}
	}

}
