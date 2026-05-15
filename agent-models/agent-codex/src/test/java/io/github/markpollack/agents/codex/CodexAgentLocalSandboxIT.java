/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.codex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.agents.tck.AbstractAgentModelTCK;

import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK test implementation for CodexAgentModel with LocalSandbox.
 *
 * <p>
 * Uses LOOSE-mode defaults (skipGitCheck=true) so tests run in any directory without
 * requiring git init. This matches the AgentClientMode.LOOSE behavior that users get out
 * of the box.
 *
 * @author Spring AI Community
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Codex CLI not available in CI environment")
class CodexAgentLocalSandboxIT extends AbstractAgentModelTCK {

	@BeforeEach
	void setUp() {
		// Codex CLI requires an OpenAI API key
		String apiKey = System.getenv("OPENAI_API_KEY");
		assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY must be set for Codex integration tests");

		try {
			// No git init needed — LOOSE mode defaults skipGitCheck=true
			this.sandbox = new LocalSandbox(tempDir);

			// Create Codex client with LOOSE-mode defaults
			ExecuteOptions executeOptions = ExecuteOptions.builder()
				.dangerouslyBypassSandbox(true)
				.timeout(Duration.ofMinutes(3))
				.skipGitCheck(true)
				.build();

			CodexClient codexClient = CodexClient.create(executeOptions, tempDir);

			// Create agent options with LOOSE-mode defaults
			CodexAgentOptions options = CodexAgentOptions.builder()
				.model("gpt-5.4-mini")
				.timeout(Duration.ofMinutes(3))
				.dangerouslyBypassSandbox(true)
				.skipGitCheck(true)
				.build();

			// Create agent model
			this.agentModel = new CodexAgentModel(codexClient, options, sandbox);

			// Verify Codex CLI is available before running tests
			assumeTrue(agentModel.isAvailable(), "Codex CLI must be available for integration tests");
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
