/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real producer smoke; Journal qualification occurs in a separate consumer. */
@Tag("live")
class ClaudeAgentJournalCaptureIT {

	@Test
	void acquiresRealToolInvocation() throws Exception {
		Path root = Path.of(System.getProperty("client.capture.smoke.directory"));
		Files.createDirectories(root);
		Path workspace = root.resolve("workspace");
		Files.createDirectory(workspace);
		String sentinel = "CLIENT_CAPTURE_SENTINEL_" + java.util.UUID.randomUUID();
		Files.writeString(workspace.resolve("sentinel.txt"), sentinel + "\n");
		String prompt = "Use the Read tool to read sentinel.txt in the working directory. Then reply with its exact contents. Do not edit any files or call any other tool.";
		var capture = new ClaudeCaptureOptions(root.resolve("acquired"), root.getFileName().toString(), "2.1.316",
				Path.of(System.getProperty("user.home"), ".claude", "projects"),
				Map.of("client_commit", System.getProperty("client.capture.commit"), "sdk_commit",
						System.getProperty("client.capture.sdk.commit")));
		try (var model = ClaudeAgentModel.builder()
			.capture(capture)
			.workingDirectory(workspace)
			.timeout(Duration.ofMinutes(2))
			.claudePath(System.getProperty("client.capture.claude.path", "claude"))
			.defaultOptions(ClaudeAgentOptions.builder().model("claude-sonnet-4-6").yolo(true).maxTurns(3).build())
			.build()) {
			var response = io.github.markpollack.agents.client.AgentClient.create(model)
				.goal(prompt)
				.workingDirectory(workspace)
				.run();
			var result = response.getAgentResponse();
			var reference = ClaudeAcquisition.from(result);
			assertThat(reference.complete()).isTrue();
			assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
			assertThat(result.getResult().getOutput()).contains(sentinel);
			assertThat(Files.readString(root.resolve("acquired/stdout.bin"))).contains("tool_use", "Read",
					"sentinel.txt", sentinel);
			assertThat(root.resolve("acquired/native/transcript.jsonl")).exists();
		}
	}

}
