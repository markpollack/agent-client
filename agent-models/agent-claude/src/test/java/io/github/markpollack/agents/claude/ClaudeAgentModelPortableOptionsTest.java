/*
 * Copyright 2025 Spring AI Community
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

package io.github.markpollack.agents.claude;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.tck.PortableAgentOptions;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests verifying that portable {@link PortableAgentOptions} fields
 * ({@code maxTurns}, {@code autoApprove}, {@code systemInstructions}) flow through
 * {@link ClaudeAgentModel#buildCLIOptions} to the correct CLI flags.
 *
 * <p>
 * These tests exercise the fallback paths in {@code buildCLIOptions} — when the request
 * carries portable options (not {@link ClaudeAgentOptions}), the portable getters should
 * map to the equivalent CLI flags.
 * </p>
 */
class ClaudeAgentModelPortableOptionsTest {

	private ClaudeAgentModel model;

	@BeforeEach
	void setUp() {
		this.model = ClaudeAgentModel.builder().build();
	}

	@AfterEach
	void tearDown() {
		this.model.close();
	}

	@Test
	@DisplayName("Portable maxTurns is passed to CLI when no Claude-specific maxTurns")
	void portableMaxTurnsPassedToCli() {
		PortableAgentOptions options = new PortableAgentOptions(3, true, null);

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getMaxTurns()).as("Portable maxTurns should map to CLI --max-turns").isEqualTo(3);
	}

	@Test
	@DisplayName("Claude-specific maxTurns takes precedence over portable")
	void claudeMaxTurnsTakesPrecedence() {
		ClaudeAgentOptions claudeOptions = ClaudeAgentOptions.builder().maxTurns(10).build();

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), claudeOptions);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getMaxTurns()).isEqualTo(10);
	}

	@Test
	@DisplayName("Portable autoApprove=true maps to DANGEROUSLY_SKIP_PERMISSIONS")
	void portableAutoApproveTrue() {
		PortableAgentOptions options = new PortableAgentOptions(null, true, null);

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getPermissionMode())
			.as("Portable autoApprove=true should map to DANGEROUSLY_SKIP_PERMISSIONS")
			.isEqualTo(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
	}

	@Test
	@DisplayName("Portable autoApprove=false does not set DANGEROUSLY_SKIP_PERMISSIONS")
	void portableAutoApproveFalse() {
		PortableAgentOptions options = new PortableAgentOptions(null, false, null);

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getPermissionMode())
			.as("Portable autoApprove=false should not set DANGEROUSLY_SKIP_PERMISSIONS")
			.isNotEqualTo(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
	}

	@Test
	@DisplayName("Portable systemInstructions maps to CLI --append-system-prompt")
	void portableSystemInstructionsPassedToCli() {
		PortableAgentOptions options = new PortableAgentOptions(null, true, "You are a Spring Boot expert");

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getAppendSystemPrompt())
			.as("Portable systemInstructions should map to CLI --append-system-prompt")
			.isEqualTo("You are a Spring Boot expert");
	}

	@Test
	@DisplayName("Claude-specific appendSystemPrompt takes precedence over portable systemInstructions")
	void claudeSystemPromptTakesPrecedence() {
		ClaudeAgentOptions claudeOptions = ClaudeAgentOptions.builder()
			.appendSystemPrompt("Claude-specific prompt")
			.build();

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), claudeOptions);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getAppendSystemPrompt()).isEqualTo("Claude-specific prompt");
	}

	@Test
	@DisplayName("Portable effort maps to CLI --effort via extraArgs")
	void portableEffortPassedToCli() {
		PortableAgentOptions options = new PortableAgentOptions(null, true, null, null, "high");

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getExtraArgs()).as("Portable effort should map to CLI --effort")
			.containsEntry("effort", "high");
	}

	@Test
	@DisplayName("Claude-specific effort on the request maps to CLI --effort (full native range)")
	void claudeEffortOnRequestPassedToCli() {
		ClaudeAgentOptions claudeOptions = ClaudeAgentOptions.builder().effort("xhigh").build();

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), claudeOptions);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getExtraArgs()).containsEntry("effort", "xhigh");
	}

	@Test
	@DisplayName("Claude-specific default effort takes precedence over portable effort")
	void claudeEffortTakesPrecedenceOverPortable() {
		try (ClaudeAgentModel nativeModel = ClaudeAgentModel.builder()
			.defaultOptions(ClaudeAgentOptions.builder().effort("max").build())
			.build()) {

			PortableAgentOptions options = new PortableAgentOptions(null, true, null, null, "low");
			AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

			CLIOptions cliOptions = nativeModel.buildCLIOptions(request);

			assertThat(cliOptions.getExtraArgs()).as("Claude-native effort should win over the portable effort value")
				.containsEntry("effort", "max");
		}
	}

	@Test
	@DisplayName("No effort configured leaves the CLI without an --effort flag")
	void noEffortMeansNoEffortFlag() {
		PortableAgentOptions options = new PortableAgentOptions(null, true, null);

		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getExtraArgs()).doesNotContainKey("effort");
	}

	@Test
	@DisplayName("Null portable options do not cause NPE")
	void nullPortableOptionsDoNotCauseNpe() {
		AgentTaskRequest request = new AgentTaskRequest("test goal", Path.of("/tmp"), null);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions).isNotNull();
	}

	@Test
	@DisplayName("All portable options work together")
	void allPortableOptionsTogether() {
		PortableAgentOptions options = new PortableAgentOptions(5, true, "Spring Boot domain expert");

		AgentTaskRequest request = new AgentTaskRequest("create a REST app", Path.of("/tmp"), options);

		CLIOptions cliOptions = this.model.buildCLIOptions(request);

		assertThat(cliOptions.getMaxTurns()).isEqualTo(5);
		assertThat(cliOptions.getPermissionMode()).isEqualTo(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS);
		assertThat(cliOptions.getAppendSystemPrompt()).isEqualTo("Spring Boot domain expert");
	}

}
