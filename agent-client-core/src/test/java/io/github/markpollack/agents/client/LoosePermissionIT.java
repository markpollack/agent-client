/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.agents.codex.CodexAgentModel;
import io.github.markpollack.agents.codex.CodexAgentOptions;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.gemini.GeminiAgentModel;
import io.github.markpollack.agents.gemini.GeminiAgentOptions;
import io.github.markpollack.agents.geminisdk.GeminiClient;
import io.github.markpollack.claude.agent.sdk.config.ClaudeCliDiscovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests validating LOOSE-mode permission defaults across providers.
 *
 * <p>
 * These tests verify that LOOSE mode defaults allow each provider to complete
 * terminal-bench easy-tier tasks without explicit configuration beyond the mode. Derived
 * from terminal-bench permission discovery (Stage 4).
 *
 * <p>
 * Key LOOSE derivations validated:
 * <ul>
 * <li>Claude: yolo=true (already default, no new derivation)</li>
 * <li>Codex: skipGitCheck=true + fullAuto=true (workspace-write + never approval)</li>
 * <li>Gemini: yolo=true (already default, no new derivation)</li>
 * </ul>
 *
 * @author Spring AI Community
 */
class LoosePermissionIT {

	@Nested
	class ClaudeLoose {

		@TempDir
		Path tempDir;

		AgentClient client;

		@BeforeEach
		void setUp() {
			assumeTrue(ClaudeCliDiscovery.isClaudeCliAvailable(), "Claude CLI must be available");

			ClaudeAgentOptions options = ClaudeAgentOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.yolo(true)
				.timeout(Duration.ofSeconds(60))
				.build();

			ClaudeAgentModel model = ClaudeAgentModel.builder()
				.workingDirectory(tempDir)
				.timeout(Duration.ofSeconds(60))
				.defaultOptions(options)
				.build();

			assumeTrue(model.isAvailable(), "Claude agent must be available");
			this.client = AgentClient.builder(model).defaultWorkingDirectory(tempDir).build();
		}

		@Test
		void helloWorldFileCreation() {
			AgentClientResponse response = client.run("Create a file called hello.txt. Write \"Hello, world!\" to it.");

			assertThat(response.isSuccessful()).isTrue();
			assertThat(tempDir.resolve("hello.txt")).exists();
		}

		@Test
		void fixPermissions() throws IOException {
			Path script = tempDir.resolve("process_data.sh");
			Files.writeString(script, "#!/bin/bash\necho \"Data processed successfully!\"\n");
			script.toFile().setExecutable(false);

			AgentClientResponse response = client.run(
					"A script called process_data.sh won't run. Figure out what's wrong and fix it so the script can run successfully.");

			assertThat(response.isSuccessful()).isTrue();
			assertThat(script.toFile().canExecute()).isTrue();
		}

	}

	@Nested
	class CodexLoose {

		@TempDir
		Path tempDir;

		AgentClient client;

		@BeforeEach
		void setUp() {
			String apiKey = System.getenv("OPENAI_API_KEY");
			assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY must be set");

			try {
				ExecuteOptions executeOptions = ExecuteOptions.builder()
					.fullAuto(true)
					.skipGitCheck(true)
					.timeout(Duration.ofMinutes(3))
					.build();

				CodexClient codexClient = CodexClient.create(executeOptions, tempDir);

				CodexAgentOptions options = CodexAgentOptions.builder()
					.fullAuto(true)
					.skipGitCheck(true)
					.timeout(Duration.ofMinutes(3))
					.build();

				CodexAgentModel model = new CodexAgentModel(codexClient, options, null);
				assumeTrue(model.isAvailable(), "Codex CLI must be available");
				this.client = AgentClient.builder(model).defaultWorkingDirectory(tempDir).build();
			}
			catch (Exception e) {
				assumeTrue(false, "Codex setup failed: " + e.getMessage());
			}
		}

		@Test
		void helloWorldFileCreation() {
			AgentClientResponse response = client.run("Create a file called hello.txt. Write \"Hello, world!\" to it.");

			assertThat(response.isSuccessful()).as("Codex response: %s", response.getResult()).isTrue();
			assertThat(tempDir.resolve("hello.txt")).exists();
		}

		@Test
		void fixPermissions() throws IOException {
			Path script = tempDir.resolve("process_data.sh");
			Files.writeString(script, "#!/bin/bash\necho \"Data processed successfully!\"\n");
			script.toFile().setExecutable(false);

			AgentClientResponse response = client.run(
					"A script called process_data.sh won't run. Figure out what's wrong and fix it so the script can run successfully.");

			assertThat(response.isSuccessful()).as("Codex response: %s", response.getResult()).isTrue();
			assertThat(script.toFile().canExecute()).isTrue();
		}

	}

	@Nested
	class GeminiLoose {

		@TempDir
		Path tempDir;

		AgentClient client;

		@BeforeEach
		void setUp() {
			String apiKey = System.getenv("GEMINI_API_KEY");
			if (apiKey == null) {
				apiKey = System.getenv("GOOGLE_API_KEY");
			}
			assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY or GOOGLE_API_KEY must be set");

			try {
				GeminiAgentOptions options = GeminiAgentOptions.builder()
					.model("gemini-2.5-flash")
					.yolo(true)
					.timeout(Duration.ofMinutes(3))
					.build();

				GeminiAgentModel model = new GeminiAgentModel(GeminiClient.create(), options, null);
				assumeTrue(model.isAvailable(), "Gemini CLI must be available");
				this.client = AgentClient.builder(model).defaultWorkingDirectory(tempDir).build();
			}
			catch (Exception e) {
				assumeTrue(false, "Gemini setup failed: " + e.getMessage());
			}
		}

		@Test
		void helloWorldFileCreation() {
			AgentClientResponse response = client.run("Create a file called hello.txt. Write \"Hello, world!\" to it.");

			assertThat(response.isSuccessful()).isTrue();
			assertThat(tempDir.resolve("hello.txt")).exists();
		}

		@Test
		void fixPermissions() throws IOException {
			Path script = tempDir.resolve("process_data.sh");
			Files.writeString(script, "#!/bin/bash\necho \"Data processed successfully!\"\n");
			script.toFile().setExecutable(false);

			AgentClientResponse response = client.run(
					"A script called process_data.sh won't run. Figure out what's wrong and fix it so the script can run successfully.");

			assertThat(response.isSuccessful()).isTrue();
			assertThat(script.toFile().canExecute()).isTrue();
		}

	}

}
