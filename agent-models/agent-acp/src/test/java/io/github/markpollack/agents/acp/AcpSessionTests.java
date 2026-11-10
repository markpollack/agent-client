/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.acp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One prompt turn over the ACP SDK against an offline agent fixture that streams session
 * updates and asks permission; no model call. It pins what the SDK's typed session-update
 * and permission handlers must still deliver to the fold and the response.
 */
@Timeout(60)
class AcpSessionTests {

	@TempDir
	Path root;

	private Path agent;

	private Path record;

	@BeforeEach
	void fixture() throws Exception {
		this.agent = this.root.resolve("fake-acp-agent.py");
		try (InputStream in = getClass().getResourceAsStream("/fake-acp-agent.py")) {
			Files.copy(in, this.agent);
		}
		Files.setPosixFilePermissions(this.agent, PosixFilePermissions.fromString("rwx------"));
		this.record = this.root.resolve("record.jsonl");
	}

	private AcpAgentModel model() {
		return model("converse");
	}

	private AcpAgentModel model(String mode) {
		String record = this.record.toString();
		AcpAgentProfile profile = new AcpAgentProfile() {
			public String providerKey() {
				return "fake";
			}

			public String defaultCommand() {
				return "unused";
			}

			public List<String> launchArgs(Path workingDirectory, AgentOptions options) {
				return List.of(record, "false", mode);
			}
		};
		return new AcpAgentModel(profile, this.agent.toString(), null, false);
	}

	private static AgentTaskRequest request(Path workingDirectory) {
		return request(workingDirectory, Duration.ofSeconds(20));
	}

	private static AgentTaskRequest request(Path workingDirectory, Duration timeout) {
		AgentOptions options = new AgentOptions() {
			public String getWorkingDirectory() {
				return null;
			}

			public Duration getTimeout() {
				return timeout;
			}

			public Map<String, String> getEnvironmentVariables() {
				return Map.of();
			}

			public String getModel() {
				return null;
			}

			public Map<String, McpServerDefinition> getMcpServerDefinitions() {
				return Map.of();
			}
		};
		return AgentTaskRequest.builder("hello", workingDirectory).options(options).build();
	}

	private List<JsonNode> recorded() throws Exception {
		var json = new ObjectMapper();
		return Files.readAllLines(this.record).stream().map(line -> {
			try {
				return json.readTree(line);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}).toList();
	}

	@Test
	void sessionUpdatesReachTheAnswerAndTheFold() {
		AgentResponse response = model().call(request(this.root));
		Map<String, Object> fields = response.getMetadata().getProviderFields();
		assertThat(fields).containsEntry("successful", true);
		assertThat(response.getText()).isEqualTo("Hello world");
		assertThat(fields).containsEntry("thoughtChunkCount", 1).containsEntry("toolCallCount", 1);
		assertThat(fields.get("unknownUpdateKinds")).as("a kind no SDK models is counted, not dropped")
			.isEqualTo(Map.of("future_kind_no_sdk_models", 1));
	}

	@Test
	void aPermissionRequestIsAnsweredWithTheAgentsAllowOption() throws Exception {
		model().call(request(this.root));
		JsonNode answer = recorded().stream()
			.filter(node -> node.has("permissionAnswer"))
			.findFirst()
			.orElseThrow()
			.path("permissionAnswer")
			.path("outcome");
		assertThat(answer.path("outcome").asText()).isEqualTo("selected");
		assertThat(answer.path("optionId").asText()).isEqualTo("yes");
	}

	@Test
	void aRelativeWorkingDirectoryIsSentAsAnAbsoluteCwd() throws Exception {
		AgentResponse response = model().call(request(Path.of(".")));
		assertThat(response.getMetadata().getProviderFields()).containsEntry("successful", true);
		JsonNode session = recorded().stream().filter(node -> node.has("cwd")).findFirst().orElseThrow();
		assertThat(Path.of(session.path("cwd").asText())).isAbsolute();
	}

	@Test
	@Timeout(30)
	void theRunTimeoutStillBoundsAPromptTurn() throws Exception {
		AgentResponse response = model("hang").call(request(this.root, Duration.ofSeconds(2)));
		assertThat(response.getMetadata().getProviderFields()).containsEntry("successful", false);
		assertThat(recorded()).as("the turn is cancelled at the agent").anyMatch(node -> node.has("cancelled"));
	}

}
