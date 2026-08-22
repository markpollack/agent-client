/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.transport;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the command line, not just the options object.
 *
 * <p>
 * SDK flag mappings drift silently as CLIs evolve, and an options object that looks right
 * proves nothing about the argv that reaches the process. These run without a Grok CLI
 * installed.
 */
class CLITransportCommandTest {

	private static final String CLI = "/usr/local/bin/grok";

	private static List<String> command(ExecuteOptions options) {
		return CLITransport.buildCommand(CLI, "review this", options, null);
	}

	@Test
	void theDefaultInvocationIsHeadlessStreamingJsonAndVerbatim() {
		List<String> command = command(ExecuteOptions.defaults());

		assertThat(command.get(0)).isEqualTo(CLI);
		assertThat(command).containsSequence("--output-format", "streaming-json");
		// The prompt must reach the model exactly as written, or two providers were not
		// given the same instruction.
		assertThat(command).contains("--verbatim");
		assertThat(command).containsSequence("--single", "review this");
		assertThat(command.get(command.size() - 1)).isEqualTo("review this");
	}

	@Test
	void modelAndEffortMapToTheirFlags() {
		List<String> command = command(ExecuteOptions.builder().model("grok-4.6").reasoningEffort("high").build());

		assertThat(command).containsSequence("--model", "grok-4.6");
		assertThat(command).containsSequence("--reasoning-effort", "high");
	}

	@Test
	void permissionModeUsesTheCliSpelling() {
		List<String> command = command(
				ExecuteOptions.builder().permissionMode(PermissionMode.BYPASS_PERMISSIONS).build());

		// camelCase, not the enum's SCREAMING_SNAKE name.
		assertThat(command).containsSequence("--permission-mode", "bypassPermissions");
	}

	@Test
	void jsonSchemaIsPassedInline() {
		String schema = "{\"type\":\"object\"}";
		List<String> command = command(ExecuteOptions.builder().jsonSchema(schema).build());

		assertThat(command).containsSequence("--json-schema", schema);
		assertThat(command).containsSequence("--output-format", "streaming-json");
	}

	@Test
	void workingDirectoryIsPassedAsAFlagNotOnlyAsProcessCwd() {
		List<String> command = command(ExecuteOptions.builder().workingDirectory(Path.of("/tmp/packet")).build());

		assertThat(command).containsSequence("--cwd", "/tmp/packet");
	}

	@Test
	void toolAllowAndDenyListsAreDistinctFlags() {
		List<String> command = command(ExecuteOptions.builder()
			.allowedTools(List.of("Read", "Grep"))
			.disallowedTools(List.of("Bash"))
			.build());

		assertThat(command).containsSequence("--tools", "Read,Grep");
		assertThat(command).containsSequence("--deny", "Bash");
	}

	@Test
	void maxTurnsAndWebSearchAreMapped() {
		List<String> command = command(ExecuteOptions.builder().maxTurns(12).disableWebSearch(true).build());

		assertThat(command).containsSequence("--max-turns", "12");
		assertThat(command).contains("--disable-web-search");
	}

	@Test
	void resumingASessionAddsResumeWithTheId() {
		List<String> command = CLITransport.buildCommand(CLI, "carry on", ExecuteOptions.defaults(),
				"01a020d9-e9cf-7da1-b70e-a6ef6781c75d");

		assertThat(command).containsSequence("--resume", "01a020d9-e9cf-7da1-b70e-a6ef6781c75d");
	}

	@Test
	void unsetOptionsEmitNoFlags() {
		List<String> command = command(ExecuteOptions.defaults());

		assertThat(command).doesNotContain("--model", "--reasoning-effort", "--permission-mode", "--max-turns",
				"--json-schema", "--tools", "--deny", "--cwd", "--resume", "--system-prompt-override");
	}

}
