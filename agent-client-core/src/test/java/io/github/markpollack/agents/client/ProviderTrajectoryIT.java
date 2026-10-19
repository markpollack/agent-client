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

/**
 * Live trajectory gates for providers wired in Work Order B. Each test is the D1 check of
 * the Journal admission criteria for its provider: it fails if capture stops happening,
 * asserting a non-null capture, at least one tool use, at least one LLM call with real
 * tokens, and a run that did not end in error.
 */
class ProviderTrajectoryIT {

	@TempDir
	Path tempDir;

	@Test
	void codexFacadePublishesToolUses() {
		assumeTrue(hasText(System.getenv("OPENAI_API_KEY")), "OPENAI_API_KEY must be set");
		// A ChatGPT-authenticated CLI rejects API-only model names (observed: "The
		// 'gpt-5.4-mini' model is not supported when using Codex with a ChatGPT
		// account"),
		// so the model comes from CODEX_TEST_MODEL and otherwise stays the CLI's own
		// default.
		String codexModel = System.getenv("CODEX_TEST_MODEL");
		CodexAgentOptions options = CodexAgentOptions.builder()
			.model(codexModel)
			.timeout(Duration.ofMinutes(3))
			.fullAuto(true)
			.skipGitCheck(true)
			.build();
		CodexClient sdk = CodexClient.create(io.github.markpollack.agents.codexsdk.types.ExecuteOptions.builder()
			.model(codexModel)
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

		assertThat(capture).as("capture must be published even though nothing else fails without it").isNotNull();
		assertThat(capture.isError()).as("run ended in error: %s", capture.textOutput()).isFalse();
		assertThat(capture.toolUses()).as("at least one tool use").isNotEmpty();
		assertThat(capture.inputTokens() + capture.outputTokens()).as("real tokens from the rollout's usage")
			.isGreaterThan(0);
		assertThat(capture.sessionId()).as("the rollout's session id").isNotBlank();
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

		assertThat(capture).as("capture must be published even though nothing else fails without it").isNotNull();
		assertThat(capture.isError()).as("run ended in error: stopReason=%s", capture.stopReason()).isFalse();
		assertThat(capture.toolUses()).as("at least one tool use").isNotEmpty();
		assertThat(capture.inputTokens()).as("real input tokens, not NOT_REPORTED").isGreaterThan(0);
		assertThat(capture.outputTokens()).as("real output tokens, not NOT_REPORTED").isGreaterThan(0);
		assertThat(capture.costReported()).as("Grok reports a total cost on its end line").isTrue();
	}

	@Test
	void antigravityFacadePublishesToolUses() {
		io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions sdkOptions = io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions
			.builder()
			.dangerouslySkipPermissions(true)
			.timeout(Duration.ofMinutes(3))
			.build();
		AntigravityClient sdk = AntigravityClient.create(sdkOptions, tempDir);
		// Model names drift between CLI releases (observed: "gemini-3.1-pro-high is not
		// recognized"), so the model comes from ANTIGRAVITY_TEST_MODEL, else the CLI's
		// default.
		AntigravityAgentModel model = new AntigravityAgentModel(sdk,
				AntigravityAgentOptions.builder()
					.model(System.getenv("ANTIGRAVITY_TEST_MODEL"))
					.dangerouslySkipPermissions(true)
					.timeout(Duration.ofMinutes(3))
					.build());
		assumeTrue(model.isAvailable(), "Antigravity CLI must be available and authenticated");

		AgentClientResponse response = AgentClient.builder(model)
			.defaultWorkingDirectory(tempDir)
			.build()
			.run("Use the run_command tool to run pwd once, then report the result.");
		AntigravityPhaseCapture capture = response.getPhaseCapture();

		assertThat(capture).as("capture must be published even though nothing else fails without it").isNotNull();
		assertThat(capture.isError())
			.as("run ended in error: status=%s message=%s", capture.status(), capture.errorMessage())
			.isFalse();
		assertThat(capture.toolUses()).as("at least one tool use").isNotEmpty();
		assertThat(capture.calls()).as("at least one LLM call with per-call usage").isNotEmpty();
		assertThat(capture.inputTokens() + capture.outputTokens()).as("real tokens").isGreaterThan(0);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
