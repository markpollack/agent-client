/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.NativeGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Antigravity seals a one-shot generation whose profile has no Journal qualifier. */
@Timeout(25)
class AntigravityGenerationTest {

	@TempDir
	Path root;

	@Test
	void oneShotSealsADeclaredUnqualifiedProfile() throws Exception {
		Path script = this.root.resolve("child.py");
		try (var in = getClass().getResourceAsStream("/antigravity-child.py")) {
			Files.copy(in, script);
		}
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		var provider = AntigravityClientProvider.builder()
			.executable(script.toString())
			.workingDirectory(this.root)
			.acquisitionDirectory(this.root.resolve("captures"), "1.4.6")
			.build();
		var probe = new GenerationProbe();
		try (var client = AgentClient.builder(provider)
			.defaultOptions(DefaultAgentOptions.builder()
				.workingDirectory(this.root.toString())
				.timeout(Duration.ofSeconds(8))
				.model("fixture-model")
				.build())
			.defaultWorkingDirectory(this.root)
			.executionRecorder(probe)
			.build()) {
			var execution = client.goal("success").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			var decision = execution.decision().orElseThrow();
			var generation = probe.sealed.get(5, TimeUnit.SECONDS).orElseThrow();
			assertThat(generation.acquisitionProfile()).isEqualTo(AntigravityAcquisition.PROFILE);
			assertThat(generation.members())
				.containsExactly(new NativeGeneration.Member(decision.executionId(), decision.decisionId()));
			assertThat(NativeGeneration.sha256(generation.manifest())).isEqualTo(generation.manifestSha256());
		}
	}

}
