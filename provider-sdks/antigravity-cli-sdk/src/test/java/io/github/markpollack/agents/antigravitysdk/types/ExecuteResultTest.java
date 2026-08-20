/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.types;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses the envelope Antigravity actually emits, and catches the failure it will not
 * report.
 *
 * <p>
 * The fixture is a verbatim capture from agy 1.1.13.
 */
class ExecuteResultTest {

	private static final String ENVELOPE = """
			{"conversation_id":"fc8e2caf-a03f-4fdb-b738-a49745a1207b","status":"SUCCESS",\
			"response":"OK\\n","duration_seconds":8.957684329,"num_turns":1,\
			"usage":{"input_tokens":14106,"output_tokens":276,"thinking_tokens":275,\
			"cache_read_tokens":0,"total_tokens":14382}}
			""";

	@Test
	void readsResponseConversationAndUsage() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, "", 0, Duration.ofSeconds(9));

		assertThat(result.isStructured()).isTrue();
		assertThat(result.getResponse()).isEqualTo("OK\n");
		assertThat(result.getConversationId()).isEqualTo("fc8e2caf-a03f-4fdb-b738-a49745a1207b");
		assertThat(result.getStatus()).isEqualTo("SUCCESS");
		assertThat(result.getInputTokens()).isEqualTo(14106);
		assertThat(result.getOutputTokens()).isEqualTo(276);
		assertThat(result.getThinkingTokens()).isEqualTo(275);
		assertThat(result.getTotalTokens()).isEqualTo(14382);
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void aCleanRunReportsNoDenials() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, "", 0, Duration.ofSeconds(9));

		assertThat(result.isSoftDenied()).isFalse();
		assertThat(result.getPermissionNotices()).isEmpty();
	}

	@Test
	void aDeniedToolCallIsDetectedFromTheEnvelopeWhereThisBuildReportsIt() {
		// Verbatim from agy 1.1.13 with permissions withheld. Note stderr is EMPTY: the
		// documentation says these notices go to stderr, and this build does not.
		String denied = "{\"conversation_id\": \"bf090b9f-c960-4b9e-9eeb-730f0b63df6a\", \"status\": \"ERROR\", \"response\": \"\", \"error\": \"permission check failed for command \\\"pwd\\\": user denied permission to run command:\\npwd\", \"duration_seconds\": 12.0, \"num_turns\": 1, \"usage\": {\"input_tokens\": 11000, \"output_tokens\": 118, \"thinking_tokens\": 100, \"cache_read_tokens\": 0, \"total_tokens\": 11118}}";

		ExecuteResult result = ExecuteResult.parse(denied, "", 0, Duration.ofSeconds(12));

		assertThat(result.isSoftDenied()).isTrue();
		assertThat(result.getPermissionNotices()).isNotEmpty();
		assertThat(result.isSuccessful()).isFalse();
	}

	@Test
	void aDeniedToolCallIsAlsoDetectedFromStderr() {
		// Kept so the detector survives a build that follows the documented behaviour.
		String stderr = """
				Tool `run_command` requires approval and was not run.
				Re-run with --dangerously-skip-permissions to allow it.
				""";

		ExecuteResult result = ExecuteResult.parse(ENVELOPE, stderr, 0, Duration.ofSeconds(9));

		assertThat(result.getStatus()).isEqualTo("SUCCESS");
		assertThat(result.getExitCode()).isZero();
		assertThat(result.isSoftDenied()).isTrue();
		assertThat(result.getPermissionNotices()).hasSize(2);
	}

	@Test
	void anErrorStatusWithACompleteResponseStillCountsAsWorkDone() {
		// Verbatim shape from agy 1.1.13: a correct answer alongside an unrelated
		// internal
		// complaint that poisons the status field. Trusting the status here would discard
		// a
		// perfectly good run.
		String noisy = "{\"conversation_id\": \"c180a1a2-f453-49ec-993f-8b25fc87f159\", \"status\": \"ERROR\", \"response\": \"{\\\"answer\\\":\\\"the answer is 42\\\"}\\n\", \"error\": \"search directory /workspace does not exist\", \"duration_seconds\": 40.0, \"num_turns\": 1, \"usage\": {\"input_tokens\": 26000, \"output_tokens\": 619, \"thinking_tokens\": 400, \"cache_read_tokens\": 0, \"total_tokens\": 26619}}";

		ExecuteResult result = ExecuteResult.parse(noisy, "", 0, Duration.ofSeconds(40));

		assertThat(result.isReportedSuccessful()).isFalse();
		assertThat(result.getError()).contains("/workspace does not exist");
		assertThat(result.hasResponse()).isTrue();
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void anEmptyResponseIsNotSuccessNoMatterWhatTheStatusSays() {
		String empty = ENVELOPE.replace("\"response\":\"OK\\n\"", "\"response\":\"\"");

		ExecuteResult result = ExecuteResult.parse(empty, "", 0, Duration.ofSeconds(9));

		assertThat(result.isReportedSuccessful()).isTrue();
		assertThat(result.isSuccessful()).isFalse();
	}

	@Test
	void aRejectedInvocationCarriesItsReasonInTheEnvelopeNotOnStderr() {
		// Verbatim from agy 1.1.13 when --model and --effort disagree.
		String rejected = "{\"conversation_id\": \"\", \"status\": \"ERROR\", \"response\": \"\", \"error\": \"invalid model selection (--model \\\"gemini-3.1-pro-high\\\" --effort \\\"low\\\"): --model gemini-3.1-pro-high conflicts with --effort=low\", \"duration_seconds\": 0, \"num_turns\": 0, \"usage\": {\"input_tokens\": 0, \"output_tokens\": 0, \"thinking_tokens\": 0, \"cache_read_tokens\": 0, \"total_tokens\": 0}}";

		ExecuteResult result = ExecuteResult.parse(rejected, "", 0, Duration.ofSeconds(1));

		// stderr is empty for this failure, so without reading the envelope's error field
		// the run looks like an agent that simply produced nothing.
		assertThat(result.isStructured()).isTrue();
		assertThat(result.isSuccessful()).isFalse();
		assertThat(result.getError()).contains("conflicts with --effort");
		assertThat(result.isSoftDenied()).isFalse();
	}

	@Test
	void aSuccessfulRunHasNoError() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, "", 0, Duration.ofSeconds(9));

		assertThat(result.getError()).isNull();
	}

	@Test
	void keepsUnparseableOutputVerbatim() {
		ExecuteResult result = ExecuteResult.parse("authentication required", "", 1, Duration.ofSeconds(1));

		assertThat(result.isStructured()).isFalse();
		assertThat(result.getResponse()).isEqualTo("authentication required");
		assertThat(result.isSuccessful()).isFalse();
	}

}
