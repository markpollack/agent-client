/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.transport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.antigravitysdk.types.ExecutionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the command line, not just the options object. Runs without an {@code agy}
 * installed.
 */
class CLITransportCommandTest {

	private static final String CLI = "/usr/local/bin/agy";

	private static List<String> command(ExecuteOptions options) {
		return CLITransport.buildCommand(CLI, "review this", options, null);
	}

	@Test
	void theDefaultInvocationIsPrintModeWithJsonOutput() {
		List<String> command = command(ExecuteOptions.defaults());

		assertThat(command.get(0)).isEqualTo(CLI);
		assertThat(command).containsSequence("--output-format", "json");
		assertThat(command).containsSequence("--print", "review this");
		assertThat(command.get(command.size() - 1)).isEqualTo("review this");
	}

	@Test
	void printTimeoutIsAlwaysSetBecauseTheCliDefaultIsFiveMinutes() {
		List<String> command = command(ExecuteOptions.builder().timeout(Duration.ofMinutes(30)).build());

		// Left unset, the CLI would stop after five minutes and the truncated run would
		// be
		// indistinguishable from the agent finishing early.
		assertThat(command).containsSequence("--print-timeout", "1800s");
	}

	@Test
	void thereIsNoCwdFlagButTheWorkingDirectoryIsDeclaredAsTheWorkspace() {
		List<String> command = command(ExecuteOptions.builder().workingDirectory(Path.of("/tmp/packet")).build());

		// agy has no --cwd, so a --cwd here would be silently ignored.
		assertThat(command).doesNotContain("--cwd");

		// Setting the process working directory is necessary but not sufficient: agy
		// only writes into a directory that is part of the workspace. Verified against
		// agy 1.1.17 — omit this flag and the CLI answers "I placed it in your scratch
		// directory ... since there wasn't an active workspace", writes to the shared
		// ~/.gemini/antigravity-cli/scratch, and still reports the task done.
		assertThat(command).containsSequence("--add-dir", "/tmp/packet");
	}

	@Test
	void theWorkingDirectoryIsNotDeclaredTwiceWhenAlsoListedAsAnAdditionalDirectory() {
		List<String> command = command(ExecuteOptions.builder()
			.workingDirectory(Path.of("/tmp/packet"))
			.additionalDirectories(List.of(Path.of("/tmp/packet"), Path.of("/tmp/other")))
			.build());

		assertThat(command.stream().filter("/tmp/packet"::equals)).hasSize(1);
		assertThat(command).containsSequence("--add-dir", "/tmp/other");
	}

	@Test
	void additionalDirectoriesEachGetTheirOwnAddDirFlag() {
		List<String> command = command(ExecuteOptions.builder()
			.additionalDirectories(List.of(Path.of("/tmp/one"), Path.of("/tmp/two")))
			.build());

		assertThat(command).containsSequence("--add-dir", "/tmp/one");
		assertThat(command).containsSequence("--add-dir", "/tmp/two");
	}

	@Test
	void effortIsDroppedWhenTheModelSlugAlreadyCarriesIt() {
		List<String> command = command(ExecuteOptions.builder().model("gemini-3.1-pro-high").effort("low").build());

		// Passing both is a hard error: "invalid model selection (--model
		// gemini-3.1-pro-high --effort low)". The run returns status ERROR with no
		// output,
		// so a portable caller asking for low effort would silently get nothing at all.
		assertThat(command).doesNotContain("--effort");
		assertThat(command).containsSequence("--model", "gemini-3.1-pro-high");
	}

	@Test
	void effortIsPassedWhenTheModelSlugDoesNotCarryIt() {
		List<String> command = command(ExecuteOptions.builder().model("claude-sonnet-4-6").effort("high").build());

		assertThat(command).containsSequence("--effort", "high");
	}

	@Test
	void effortSuffixDetectionIsCaseInsensitiveAndOnlyMatchesTheSuffix() {
		assertThat(CLITransport.shouldPassEffort("Gemini-3.7-Flash-MEDIUM", "low")).isFalse();
		assertThat(CLITransport.shouldPassEffort("gemini-3.1-pro-low", "high")).isFalse();
		// "-high" only counts at the end; a model merely containing it still takes the
		// flag.
		assertThat(CLITransport.shouldPassEffort("high-throughput-model", "high")).isTrue();
		assertThat(CLITransport.shouldPassEffort(null, "high")).isTrue();
		assertThat(CLITransport.shouldPassEffort("gemini-3.1-pro-high", null)).isFalse();
	}

	@Test
	void modelAndModeMapToTheirFlags() {
		List<String> command = command(
				ExecuteOptions.builder().model("gemini-3.1-pro-high").mode(ExecutionMode.ACCEPT_EDITS).build());

		assertThat(command).containsSequence("--model", "gemini-3.1-pro-high");
		// kebab-case, not the enum's SCREAMING_SNAKE name.
		assertThat(command).containsSequence("--mode", "accept-edits");
	}

	@Test
	void skippingPermissionsIsASingleFlagAndIsOffByDefault() {
		assertThat(command(ExecuteOptions.defaults())).doesNotContain("--dangerously-skip-permissions");
		assertThat(command(ExecuteOptions.builder().dangerouslySkipPermissions(true).build()))
			.contains("--dangerously-skip-permissions");
	}

	@Test
	void jsonSchemaIsPassedInline() {
		String schema = "{\"type\":\"object\"}";

		assertThat(command(ExecuteOptions.builder().jsonSchema(schema).build())).containsSequence("--json-schema",
				schema);
	}

	@Test
	void resumingUsesConversationNotSession() {
		List<String> command = CLITransport.buildCommand(CLI, "carry on", ExecuteOptions.defaults(),
				"fc8e2caf-a03f-4fdb-b738-a49745a1207b");

		assertThat(command).containsSequence("--conversation", "fc8e2caf-a03f-4fdb-b738-a49745a1207b");
	}

	@Test
	void timeoutsAreFormattedInGoDurationSyntaxAndNeverRoundToZero() {
		assertThat(CLITransport.formatTimeout(Duration.ofSeconds(90))).isEqualTo("90s");
		assertThat(CLITransport.formatTimeout(Duration.ofMillis(200))).isEqualTo("1s");
	}

}
