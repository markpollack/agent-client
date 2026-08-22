/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.agents.antigravity.AntigravityAgentModel;
import io.github.markpollack.agents.antigravity.AntigravityAgentOptions;
import io.github.markpollack.agents.antigravitysdk.AntigravityClient;
import io.github.markpollack.agents.codex.CodexAgentModel;
import io.github.markpollack.agents.codex.CodexAgentOptions;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.grok.GrokAgentModel;
import io.github.markpollack.agents.grok.GrokAgentOptions;
import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.journal.antigravity.AntigravityPhaseCapture;
import io.github.markpollack.journal.codex.CodexPhaseCapture;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live trajectory gates for providers wired in Work Order B. */
class ProviderTrajectoryIT {

	@TempDir
	Path tempDir;

	@Test
	void codexFacadePublishesToolUses() {
		assumeTrue(hasText(System.getenv("OPENAI_API_KEY")), "OPENAI_API_KEY must be set");
		CodexAgentOptions options = CodexAgentOptions.builder()
			.model("gpt-5.4-mini")
			.timeout(Duration.ofMinutes(3))
			.fullAuto(true)
			.skipGitCheck(true)
			.build();
		CodexClient sdk = CodexClient.create(io.github.markpollack.agents.codexsdk.types.ExecuteOptions.builder()
			.model("gpt-5.4-mini")
			.timeout(Duration.ofMinutes(3))
			.fullAuto(true)
			.skipGitCheck(true)
			.build(), tempDir);
		CodexAgentModel model = new CodexAgentModel(sdk, options, null);
		assumeTrue(model.isAvailable(), "Codex CLI must be available");

		AgentClientResponse response = AgentClient.builder(model)
			.defaultWorkingDirectory(tempDir)
			.build()
			.run("Use apply_patch to create capture.txt containing the word capture.");
		CodexPhaseCapture capture = response.getPhaseCapture();

		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).isNotEmpty();
	}

	@Test
	void grokFacadePublishesToolUses() {
		io.github.markpollack.agents.groksdk.types.ExecuteOptions sdkOptions = io.github.markpollack.agents.groksdk.types.ExecuteOptions
			.builder()
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.timeout(Duration.ofMinutes(3))
			.build();
		GrokClient sdk = GrokClient.create(sdkOptions, tempDir);
		GrokAgentModel model = new GrokAgentModel(sdk,
				GrokAgentOptions.builder()
					.model("grok-4.6")
					.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
					.timeout(Duration.ofMinutes(3))
					.build());
		assumeTrue(model.isAvailable(), "Grok CLI must be available and authenticated");

		AgentClientResponse response = AgentClient.builder(model)
			.defaultWorkingDirectory(tempDir)
			.build()
			.run("Use the shell tool to run pwd once, then report the result.");
		GrokPhaseCapture capture = response.getPhaseCapture();

		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).isNotEmpty();
	}

	@Test
	void antigravityFacadePublishesToolUses() {
		io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions sdkOptions = io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions
			.builder()
			.dangerouslySkipPermissions(true)
			.timeout(Duration.ofMinutes(3))
			.build();
		AntigravityClient sdk = AntigravityClient.create(sdkOptions, tempDir);
		AntigravityAgentModel model = new AntigravityAgentModel(sdk,
				AntigravityAgentOptions.builder()
					.model("gemini-3.1-pro-high")
					.dangerouslySkipPermissions(true)
					.timeout(Duration.ofMinutes(3))
					.build());
		assumeTrue(model.isAvailable(), "Antigravity CLI must be available and authenticated");

		AgentClientResponse response = AgentClient.builder(model)
			.defaultWorkingDirectory(tempDir)
			.build()
			.run("Use the run_command tool to run pwd once, then report the result.");
		AntigravityPhaseCapture capture = response.getPhaseCapture();

		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).isNotEmpty();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
