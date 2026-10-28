/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeAgentModelEnvironmentTest {

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void portableOverridesReachOneShotChildWithoutChangingDefaults(@TempDir Path directory) throws Exception {
		Path executable = directory.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/bin/sh
						printf '%s\n' "$CLIENT_DEFAULT" "$CLIENT_OVERRIDE" "$CLIENT_REQUEST" "$PATH" > observed-env
						echo '{"type":"system","subtype":"init","session_id":"fixture"}'
						IFS= read -r line
						echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"ok","session_id":"fixture","total_cost_usd":0,"usage":{"input_tokens":1,"output_tokens":1}}'
						""");
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		var defaults = ClaudeAgentOptions.builder()
			.environmentVariables(Map.of("CLIENT_DEFAULT", "retained", "CLIENT_OVERRIDE", "default"))
			.build();
		AgentOptions requestOptions = mock(AgentOptions.class);
		when(requestOptions.getEnvironmentVariables())
			.thenReturn(Map.of("CLIENT_OVERRIDE", "request", "CLIENT_REQUEST", "added"));
		try (var model = ClaudeAgentModel.builder()
			.defaultOptions(defaults)
			.claudePath(executable.toString())
			.timeout(Duration.ofSeconds(5))
			.archiveTranscript(false)
			.build()) {
			model.call(new AgentTaskRequest("test", directory, requestOptions));
			assertThat(Files.readAllLines(directory.resolve("observed-env"))).containsExactly("retained", "request",
					"added", System.getenv("PATH"));
			assertThat(model.buildCLIOptions(new AgentTaskRequest("next", directory, null)).env())
				.containsExactlyInAnyOrderEntriesOf(defaults.getEnvironmentVariables());
			assertThat(defaults.getEnvironmentVariables())
				.containsExactlyInAnyOrderEntriesOf(Map.of("CLIENT_DEFAULT", "retained", "CLIENT_OVERRIDE", "default"));
		}
	}

	@Test
	void nullPortableEnvironmentKeepsDefaults() {
		var defaults = ClaudeAgentOptions.builder().environmentVariables(Map.of("CLIENT_DEFAULT", "retained")).build();
		try (var model = ClaudeAgentModel.builder().defaultOptions(defaults).build()) {
			var options = model.buildCLIOptions(new AgentTaskRequest("test", Path.of("."), mock(AgentOptions.class)));
			assertThat(options.env()).containsExactlyInAnyOrderEntriesOf(defaults.getEnvironmentVariables());
		}
	}

}
