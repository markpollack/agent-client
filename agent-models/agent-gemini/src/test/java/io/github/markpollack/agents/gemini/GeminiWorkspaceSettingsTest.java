/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.gemini;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.markpollack.agents.geminisdk.GeminiClient;
import io.github.markpollack.agents.geminisdk.transport.CLIOptions;
import io.github.markpollack.agents.geminisdk.types.Cost;
import io.github.markpollack.agents.geminisdk.types.Metadata;
import io.github.markpollack.agents.geminisdk.types.QueryResult;
import io.github.markpollack.agents.geminisdk.types.ResultStatus;
import io.github.markpollack.agents.geminisdk.types.Usage;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.agents.tck.PortableMcpAgentOptions;
import io.github.markpollack.sandbox.ExecSpec;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.sandbox.Sandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Client's MCP servers must not touch the workspace's own Gemini settings, and no MCP
 * secret may be written into the workspace. Offline: a fake {@code gemini} records what
 * it was given.
 */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class GeminiWorkspaceSettingsTest {

	private static final String SECRET = "s3cret-token-value";

	private static final String THEIR_SETTINGS = """
			{
			  "general": { "vimMode": true },
			  "mcpServers": { "theirs": { "command": "their-server" } }
			}
			""";

	@TempDir
	Path workspace;

	@TempDir
	Path records;

	private GeminiClient client;

	@BeforeEach
	void fakeGemini() throws Exception {
		Path script = this.records.resolve("fake-gemini");
		Files.writeString(script, """
				#!/bin/sh
				out="$1"
				echo "${GEMINI_CLI_SYSTEM_SETTINGS_PATH:-none}" > "$out/settings-path"
				if [ -n "$GEMINI_CLI_SYSTEM_SETTINGS_PATH" ]; then
				  cat "$GEMINI_CLI_SYSTEM_SETTINGS_PATH" > "$out/session-settings"
				  ls -l "$GEMINI_CLI_SYSTEM_SETTINGS_PATH" | cut -c1-10 > "$out/session-mode"
				fi
				grep -rl "SECRET" . > "$out/secrets-in-workspace" 2>/dev/null
				echo fixture
				exit "$2"
				""".replace("SECRET", SECRET));
		assertThat(script.toFile().setExecutable(true)).isTrue();
		this.client = mock(GeminiClient.class);
		Metadata metadata = Metadata.builder()
			.model("gemini-2.5-flash")
			.timestamp(Instant.now())
			.duration(Duration.ofMillis(10))
			.usage(Usage.of(1, 1))
			.cost(Cost.of(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO))
			.build();
		when(this.client.parseResult(anyString(), any(CLIOptions.class)))
			.thenReturn(new QueryResult(List.of(), metadata, ResultStatus.SUCCESS));
	}

	@Test
	void existingWorkspaceSettingsAreByteIdenticalAfterASuccessfulRun() throws Exception {
		byte[] before = writeTheirSettings();
		AgentResponse response = run(0, new LocalSandbox(this.workspace));
		assertThat(response.isSuccessful()).isTrue();
		assertWorkspaceUntouched(before);
		assertServersReachedGeminiOutsideTheWorkspace();
	}

	@Test
	void existingWorkspaceSettingsAreByteIdenticalAfterAFailedRun() throws Exception {
		byte[] before = writeTheirSettings();
		AgentResponse response = run(3, new LocalSandbox(this.workspace));
		assertThat(response.isSuccessful()).isFalse();
		assertWorkspaceUntouched(before);
	}

	@Test
	void noSecretOrClientSettingsFileIsWrittenIntoAnEmptyWorkspace() throws Exception {
		run(0, new LocalSandbox(this.workspace));
		assertThat(Files.readString(this.records.resolve("secrets-in-workspace")).trim())
			.as("no MCP secret in the workspace during the run")
			.isEmpty();
		try (Stream<Path> left = Files.list(this.workspace)) {
			assertThat(left).as("nothing left in the workspace").isEmpty();
		}
		assertServersReachedGeminiOutsideTheWorkspace();
	}

	@Test
	void aSandboxThatCannotSeeHostFilesRefusesMcpServersBeforeRunning() throws Exception {
		Sandbox remote = mock(Sandbox.class);
		when(remote.workDir()).thenReturn(Path.of("/work"));
		AgentResponse response = run(0, remote);
		assertThat(response.isSuccessful()).isFalse();
		assertThat(response.getText()).contains("files").contains("MCP");
		verify(remote, never()).exec(any(ExecSpec.class));
	}

	private AgentResponse run(int exitCode, Sandbox sandbox) {
		when(this.client.buildCommand(anyString(), any(CLIOptions.class))).thenReturn(List
			.of(this.records.resolve("fake-gemini").toString(), this.records.toString(), Integer.toString(exitCode)));
		var model = new GeminiAgentModel(this.client,
				GeminiAgentOptions.builder().model("gemini-2.5-flash").timeout(Duration.ofSeconds(30)).build(),
				sandbox);
		Map<String, McpServerDefinition> servers = Map.of("files", new McpServerDefinition.StdioDefinition("npx",
				List.of("-y", "files-server"), Map.of("TOKEN", SECRET)));
		return model.call(AgentTaskRequest.builder("Use the files server", this.workspace)
			.options(new PortableMcpAgentOptions(servers))
			.build());
	}

	private byte[] writeTheirSettings() throws IOException {
		Path settings = Files.createDirectories(this.workspace.resolve(".gemini")).resolve("settings.json");
		Files.writeString(settings, THEIR_SETTINGS);
		return Files.readAllBytes(settings);
	}

	private void assertWorkspaceUntouched(byte[] before) throws IOException {
		Path settings = this.workspace.resolve(".gemini/settings.json");
		assertThat(settings).as("their settings file still exists").exists();
		assertThat(Files.readAllBytes(settings)).as("their settings are byte-identical").isEqualTo(before);
		try (Stream<Path> all = Files.walk(this.workspace)) {
			assertThat(all.filter(Files::isRegularFile)).as("Client left no file in the workspace")
				.containsExactly(settings);
		}
	}

	private void assertServersReachedGeminiOutsideTheWorkspace() throws IOException {
		Path sessionPath = Path.of(Files.readString(this.records.resolve("settings-path")).trim());
		assertThat(sessionPath.toString()).as("session settings passed by env").isNotEqualTo("none");
		assertThat(sessionPath.toAbsolutePath().startsWith(this.workspace.toAbsolutePath()))
			.as("session settings live outside the workspace")
			.isFalse();
		assertThat(Files.readString(this.records.resolve("session-mode")).trim()).isEqualTo("-rw-------");
		String session = Files.readString(this.records.resolve("session-settings"));
		assertThat(session).contains("\"files\"").contains("files-server").contains(SECRET).contains("\"allowed\"");
		assertThat(sessionPath).as("session settings removed after the run").doesNotExist();
	}

}
