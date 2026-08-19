/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for QwenCodeAgentModel against a real Qwen Code CLI installation.
 *
 * <p>
 * Requires the {@code qwen} CLI to be installed and available on PATH. Skips gracefully
 * when the CLI is not found.
 * </p>
 *
 * @author Spring AI Community
 * @since 0.12.0
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Qwen Code CLI not available in CI environment")
class QwenCodeAgentModelIT {

	@TempDir
	Path tempDir;

	static boolean cliAvailable;

	@BeforeAll
	static void checkCliAvailable() {
		try {
			ProcessBuilder pb = new ProcessBuilder("qwen", "--version");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			int exitCode = process.waitFor();
			cliAvailable = (exitCode == 0);
		}
		catch (Exception e) {
			cliAvailable = false;
		}
	}

	@Test
	void testSimpleQuery() {
		assumeTrue(cliAvailable, "Qwen Code CLI must be installed");

		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder()
			.model("qwen3-coder")
			.timeout(Duration.ofMinutes(3))
			.yolo(true)
			.build();

		QwenCodeAgentModel model = new QwenCodeAgentModel(options);

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'hello.txt' with the content 'Hello from Qwen Code'", tempDir)
			.build();

		AgentResponse response = model.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResult()).isNotNull();
		assertThat(response.getMetadata().getModel()).isNotNull();
	}

	@Test
	void testFileCreation() throws Exception {
		assumeTrue(cliAvailable, "Qwen Code CLI must be installed");

		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder()
			.model("qwen3-coder")
			.timeout(Duration.ofMinutes(3))
			.yolo(true)
			.build();

		QwenCodeAgentModel model = new QwenCodeAgentModel(options);

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'greeting.txt' with the content 'Hello, Spring AI!'", tempDir)
			.build();

		AgentResponse response = model.call(request);

		assertThat(response).isNotNull();
		assertThat(response.isSuccessful()).isTrue();

		Path greetingFile = tempDir.resolve("greeting.txt");
		assertThat(Files.exists(greetingFile)).isTrue();
		assertThat(Files.readString(greetingFile)).contains("Hello, Spring AI!");
	}

}
