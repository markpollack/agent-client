/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.geminisdk.util.GeminiCliDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * MCP servers reach the Gemini CLI through owner-only session settings outside the
 * workspace, never through the workspace's {@code .gemini/settings.json}.
 */
class CLITransportMcpTest {

	private static final Map<String, Object> SERVERS = Map.of("files",
			Map.of("command", "npx", "args", List.of("-y", "files-server"), "env", Map.of("TOKEN", "s3cret")));

	@TempDir
	Path tempDir;

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void sessionSettingsAreOwnerOnlyAllowOnlyTheseServersAndAreRemovedOnClose() throws Exception {
		Path file;
		try (McpSessionSettings settings = McpSessionSettings.open(SERVERS, this.tempDir.resolve("absent.json"),
				null)) {
			file = Path.of(settings.environment().get(McpSessionSettings.SYSTEM_SETTINGS_ENV));
			assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE);
			assertThat(Files.getPosixFilePermissions(file.getParent())).containsExactlyInAnyOrder(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
			JsonNode json = new ObjectMapper().readTree(file.toFile());
			assertThat(json.path("mcpServers").path("files").path("command").asText()).isEqualTo("npx");
			assertThat(json.path("mcp").path("allowed").toString()).isEqualTo("[\"files\"]");
		}
		assertThat(file).doesNotExist();
		assertThat(file.getParent()).doesNotExist();
	}

	@Test
	void noServersMeansNoFileAndNoEnvironment() throws Exception {
		try (McpSessionSettings settings = McpSessionSettings.open(Map.of(), null, null)) {
			assertThat(settings.environment()).isEmpty();
		}
	}

	@Test
	void anAdministratorsSystemSettingsFileIsNotBypassed() throws Exception {
		Path admin = Files.writeString(this.tempDir.resolve("admin-settings.json"), "{}");
		assertThatThrownBy(() -> McpSessionSettings.open(SERVERS, admin, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(admin.toString())
			.hasMessageContaining("files");
	}

	@Test
	void anExistingSystemSettingsOverrideIsNotReplaced() {
		assertThatThrownBy(() -> McpSessionSettings.open(SERVERS, null, "/opt/policy/settings.json"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("/opt/policy/settings.json");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void theDirectQueryPassesSessionSettingsAndLeavesTheWorkspaceAlone() throws Exception {
		Path workspace = Files.createDirectories(this.tempDir.resolve("workspace"));
		Path theirs = Files.createDirectories(workspace.resolve(".gemini")).resolve("settings.json");
		Files.writeString(theirs, "{\"general\":{\"vimMode\":true}}\n");
		byte[] before = Files.readAllBytes(theirs);
		Path seen = this.tempDir.resolve("seen.json");
		Path script = this.tempDir.resolve("fake-gemini");
		Files.writeString(script, """
				#!/bin/sh
				cat "$GEMINI_CLI_SYSTEM_SETTINGS_PATH" > "%s"
				echo ok
				""".formatted(seen));
		assertThat(script.toFile().setExecutable(true)).isTrue();
		CLITransport transport;
		try (var discovery = mockStatic(GeminiCliDiscovery.class, CALLS_REAL_METHODS)) {
			discovery.when(GeminiCliDiscovery::findGeminiCommand).thenReturn(script.toString());
			transport = new CLITransport(workspace, Duration.ofSeconds(5));
		}
		CLIOptions options = CLIOptions.builder()
			.yoloMode(false)
			.timeout(Duration.ofSeconds(5))
			.mcpServers(SERVERS)
			.build();
		assertThat(transport.executeQuery("hello", options)).isNotEmpty();
		assertThat(Files.readString(seen)).contains("files-server");
		assertThat(Files.readAllBytes(theirs)).isEqualTo(before);
	}

	@Test
	void buildCommandIncludesAllowedMcpServerNames() {
		CLIOptions options = CLIOptions.builder()
			.mcpServers(Map.of("weather", Map.of("command", "echo"), "brave-search", Map.of("command", "npx")))
			.build();

		CLITransport transport = new CLITransport(this.tempDir, options.getTimeout());
		List<String> command = transport.buildCommand("test prompt", options);

		int flagIndex = command.indexOf("--allowed-mcp-server-names");
		assertThat(flagIndex).isGreaterThan(-1);

		String serverNames = command.get(flagIndex + 1);
		assertThat(serverNames).contains("weather");
		assertThat(serverNames).contains("brave-search");
	}

	@Test
	void buildCommandOmitsAllowedMcpServerNamesWhenNoServers() {
		CLIOptions options = CLIOptions.builder().build();

		CLITransport transport = new CLITransport(this.tempDir, options.getTimeout());
		List<String> command = transport.buildCommand("test prompt", options);

		assertThat(command).doesNotContain("--allowed-mcp-server-names");
	}

}
