/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;

import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.Provider;
import io.github.markpollack.agents.tck.ProviderParityTCK;
import io.github.markpollack.sandbox.LocalSandbox;

/**
 * Parity TCK wired for Codex provider.
 *
 * <p>
 * Uses LOOSE-mode defaults (skipGitCheck=true) so parity tests run in non-git
 * directories. Requires OPENAI_API_KEY.
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
class CodexProviderParityIT extends ProviderParityTCK {

	@Override
	protected String getProvider() {
		return Provider.CODEX;
	}

	@BeforeEach
	void setUp() {
		String apiKey = System.getenv("OPENAI_API_KEY");
		assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY must be set");

		try {
			this.sandbox = new LocalSandbox(tempDir);

			ExecuteOptions executeOptions = ExecuteOptions.builder()
				.dangerouslyBypassSandbox(true)
				.timeout(Duration.ofMinutes(3))
				.skipGitCheck(true)
				.build();

			CodexClient codexClient = CodexClient.create(executeOptions, tempDir);

			CodexAgentOptions options = CodexAgentOptions.builder()
				.model("gpt-5.4-mini")
				.timeout(Duration.ofMinutes(3))
				.dangerouslyBypassSandbox(true)
				.skipGitCheck(true)
				.build();

			this.agentModel = new CodexAgentModel(codexClient, options, sandbox);

			assumeTrue(agentModel.isAvailable(), "Codex CLI must be available");
		}
		catch (Exception e) {
			assumeTrue(false, "Failed to initialize Codex CLI: " + e.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return CodexAgentOptions.builder()
			.model("gpt-5.4-mini")
			.timeout(Duration.ofSeconds(10))
			.dangerouslyBypassSandbox(true)
			.skipGitCheck(true)
			.build();
	}

}
