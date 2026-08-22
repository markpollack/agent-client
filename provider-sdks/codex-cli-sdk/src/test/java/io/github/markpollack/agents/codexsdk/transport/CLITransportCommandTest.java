/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI-flag validation tests for {@link CLITransport#buildCommand}: verifies that
 * {@link ExecuteOptions} fields land in the actual Codex CLI argument list. These tests
 * exist because SDK flag mappings silently drift as CLIs evolve — assert the command
 * line, not just the options object. The static method is used directly so the tests run
 * without a functional Codex CLI on the machine (e.g. CI runners).
 */
class CLITransportCommandTest {

	@Test
	@DisplayName("reasoningEffort maps to -c model_reasoning_effort=\"<value>\" (quoted TOML string)")
	void reasoningEffortMapsToConfigOverride() {
		ExecuteOptions options = ExecuteOptions.builder().reasoningEffort("high").build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		int cIndex = command.indexOf("-c");
		assertThat(cIndex).as("-c config override flag should be present").isGreaterThanOrEqualTo(0);
		assertThat(command.get(cIndex + 1)).isEqualTo("model_reasoning_effort=\"high\"");
	}

	@Test
	@DisplayName("no reasoningEffort means no -c model_reasoning_effort override")
	void noReasoningEffortMeansNoOverride() {
		ExecuteOptions options = ExecuteOptions.builder().build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		assertThat(command).noneMatch(arg -> arg.startsWith("model_reasoning_effort"));
	}

	@Test
	@DisplayName("model maps to --model <value>")
	void modelMapsToFlag() {
		ExecuteOptions options = ExecuteOptions.builder().model("gpt-5.4-mini").build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		int modelIndex = command.indexOf("--model");
		assertThat(modelIndex).isGreaterThanOrEqualTo(0);
		assertThat(command.get(modelIndex + 1)).isEqualTo("gpt-5.4-mini");
	}

	@Test
	@DisplayName("full auto uses global sandbox and approval options, never an exec --full-auto flag")
	void fullAutoUsesGlobalEquivalentBeforeExec() {
		ExecuteOptions options = ExecuteOptions.builder().fullAuto(true).build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		int execIndex = command.indexOf("exec");
		int sandboxIndex = command.indexOf("--sandbox");
		int approvalIndex = command.indexOf("--ask-for-approval");
		assertThat(command).doesNotContain("--full-auto");
		assertThat(sandboxIndex).isBetween(1, execIndex - 1);
		assertThat(command.get(sandboxIndex + 1)).isEqualTo("workspace-write");
		assertThat(approvalIndex).isBetween(1, execIndex - 1);
		assertThat(command.get(approvalIndex + 1)).isEqualTo("never");
	}

	@Test
	@DisplayName("dangerous bypass is distinct from full auto")
	void dangerousBypassUsesOnlyTheExplicitUnrestrictedFlag() {
		ExecuteOptions options = ExecuteOptions.builder().dangerouslyBypassSandbox(true).build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		assertThat(command).contains("--dangerously-bypass-approvals-and-sandbox");
		assertThat(command).doesNotContain("--ask-for-approval", "--sandbox", "--full-auto");
	}

	@Test
	@DisplayName("additional directories map to repeated --add-dir flags")
	void additionalDirectoriesMapToRepeatedFlags() {
		ExecuteOptions options = ExecuteOptions.builder()
			.additionalDirectories(List.of(Path.of("/tmp/one"), Path.of("/tmp/two")))
			.build();

		List<String> command = CLITransport.buildCommand("codex", "test goal", options, null);

		assertThat(command).containsSequence("--add-dir", "/tmp/one");
		assertThat(command).containsSequence("--add-dir", "/tmp/two");
	}

	@Test
	@DisplayName("prompt follows the -- separator as the final argument")
	void promptIsFinalArgumentAfterSeparator() {
		ExecuteOptions options = ExecuteOptions.builder().reasoningEffort("minimal").build();

		List<String> command = CLITransport.buildCommand("codex", "complex, 'quoted' goal", options, null);

		assertThat(command.get(command.size() - 2)).isEqualTo("--");
		assertThat(command.get(command.size() - 1)).isEqualTo("complex, 'quoted' goal");
	}

}
