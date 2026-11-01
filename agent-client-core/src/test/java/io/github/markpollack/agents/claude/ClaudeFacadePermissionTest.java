/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class ClaudeFacadePermissionTest {

	@TempDir
	Path temp;

	@Test
	void explicitProviderFalseAndToolsReachSdkCommand() throws Exception {
		var options = ClaudeAgentOptions.builder()
			.yolo(false)
			.tools(List.of("Read", "Glob", "Grep"))
			.allowedTools(List.of("Read", "Glob", "Grep"))
			.disallowedTools(List.of("Write"))
			.build();
		var argv = invoke(options);
		assertThat(argv).containsSubsequence("--permission-mode", "default");
		assertThat(argv).containsSubsequence("--tools", "Read,Glob,Grep");
		assertThat(argv).containsSubsequence("--disallowedTools", "Write");
		assertThat(argv).containsSubsequence("--allowedTools", "Read,Glob,Grep");
		assertThat(argv).doesNotContain("--dangerously-skip-permissions", "bypassPermissions");
	}

	@Test
	void explicitProviderTrueRetainsBypass() throws Exception {
		assertThat(invoke(ClaudeAgentOptions.builder().yolo(true).build())).contains("--dangerously-skip-permissions")
			.doesNotContain("default");
	}

	@Test
	void portableFalseReachesDefaultMode() throws Exception {
		var argv = invoke(DefaultAgentOptions.builder().autoApprove(false).build());
		assertThat(argv).containsSubsequence("--permission-mode", "default");
		assertThat(argv).doesNotContain("--dangerously-skip-permissions", "bypassPermissions");
	}

	@Test
	void portableTrueRetainsBypass() throws Exception {
		assertThat(invoke(DefaultAgentOptions.builder().autoApprove(true).build()))
			.contains("--dangerously-skip-permissions");
	}

	private List<String> invoke(AgentOptions facadeOptions) throws Exception {
		Path executable = temp.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/bin/sh
						printf '%s\\n' "$@" > "$CAPTURE_ARGS"
						echo '{"type":"system","subtype":"init","session_id":"permission-fixture"}'
						IFS= read -r line
						echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"ok","session_id":"permission-fixture"}'
						""");
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		var modelOptions = facadeOptions instanceof ClaudeAgentOptions claude ? claude : new ClaudeAgentOptions();
		modelOptions.setEnvironmentVariables(Map.of("CAPTURE_ARGS", temp.resolve("argv").toString()));
		modelOptions.setTimeout(Duration.ofSeconds(5));
		try (var model = ClaudeAgentModel.builder()
			.claudePath(executable.toString())
			.workingDirectory(temp)
			.defaultOptions(modelOptions)
			.build()) {
			var response = AgentClient.builder(model)
				.defaultOptions(facadeOptions)
				.build()
				.goal("read")
				.workingDirectory(temp)
				.run();
			assertThat(response.isSuccessful()).isTrue();
		}
		return Files.readAllLines(temp.resolve("argv"));
	}

}
