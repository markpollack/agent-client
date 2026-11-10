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
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configured MCP servers reach the ACP agent's session/new, or the call is refused before
 * a session opens. Runs an offline ACP agent fixture; no model call.
 */
@Timeout(60)
class AcpMcpServersTests {

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
		this.record = this.root.resolve("session-new.jsonl");
	}

	private AcpAgentModel model(boolean httpAdvertised) {
		String record = this.record.toString();
		AcpAgentProfile profile = new AcpAgentProfile() {
			public String providerKey() {
				return "fake";
			}

			public String defaultCommand() {
				return "unused";
			}

			public List<String> launchArgs(Path workingDirectory, AgentOptions options) {
				return List.of(record, String.valueOf(httpAdvertised));
			}
		};
		return new AcpAgentModel(profile, this.agent.toString(), null, false);
	}

	private AgentTaskRequest request(Map<String, McpServerDefinition> servers) {
		AgentOptions options = new AgentOptions() {
			public String getWorkingDirectory() {
				return AcpMcpServersTests.this.root.toString();
			}

			public Duration getTimeout() {
				return Duration.ofSeconds(20);
			}

			public Map<String, String> getEnvironmentVariables() {
				return Map.of();
			}

			public String getModel() {
				return null;
			}

			public Map<String, McpServerDefinition> getMcpServerDefinitions() {
				return servers;
			}
		};
		return AgentTaskRequest.builder("hello", this.root).options(options).build();
	}

	private List<JsonNode> sessionsOpened() throws Exception {
		if (!Files.exists(this.record))
			return List.of();
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
	void stdioServersAreSentInSessionNew() throws Exception {
		var response = model(false).call(request(Map.of("files",
				new McpServerDefinition.StdioDefinition("npx", List.of("-y", "files-server"), Map.of("ROOT", "/w")))));
		assertThat(response.getText()).doesNotContain("unsupported");
		var sessions = sessionsOpened();
		assertThat(sessions).hasSize(1);
		JsonNode server = sessions.get(0).path("mcpServers").get(0);
		assertThat(server).as("stdio MCP server in session/new").isNotNull();
		assertThat(server.path("name").asText()).isEqualTo("files");
		assertThat(server.path("command").asText()).isEqualTo("npx");
		assertThat(server.path("args").toString()).isEqualTo("[\"-y\",\"files-server\"]");
		assertThat(server.path("env").get(0).path("name").asText()).isEqualTo("ROOT");
		assertThat(server.path("env").get(0).path("value").asText()).isEqualTo("/w");
	}

	@Test
	void anHttpServerIsSentWhenTheAgentAdvertisesHttp() throws Exception {
		model(true).call(request(Map.of("search",
				new McpServerDefinition.HttpDefinition("https://mcp.example/search", Map.of("Authorization", "t")))));
		JsonNode server = sessionsOpened().get(0).path("mcpServers").get(0);
		assertThat(server).isNotNull();
		assertThat(server.path("type").asText()).isEqualTo("http");
		assertThat(server.path("url").asText()).isEqualTo("https://mcp.example/search");
		assertThat(server.path("headers").get(0).path("name").asText()).isEqualTo("Authorization");
	}

	@Test
	void anHttpServerIsRefusedBeforeASessionWhenTheAgentDoesNotAdvertiseIt() throws Exception {
		var response = model(false)
			.call(request(Map.of("search", new McpServerDefinition.HttpDefinition("https://mcp.example/search"))));
		assertThat(response.getMetadata().getProviderFields()).containsEntry("successful", false);
		assertThat(response.getText()).contains("search").contains("http");
		assertThat(sessionsOpened()).as("no session opened, no prompt sent").isEmpty();
	}

}
