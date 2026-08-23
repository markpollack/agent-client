/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

	private static final String STREAM = """
			{"event":"init","conversation_id":"fc8e2caf-a03f-4fdb-b738-a49745a1207b","init":{"model":"gemini-3.1-pro-high"}}
			{"event":"step_update","step_update":{"step_type":"tool","step_index":0,"state":"SUCCESS","tool_info":{"name":"run_command","parameters":{"command":"pwd"},"output":"/tmp/work"}}}
			{"event":"result","result":{"conversation_id":"fc8e2caf-a03f-4fdb-b738-a49745a1207b","status":"SUCCESS","response":"OK\\n","duration_seconds":8.957684329,"num_turns":1,"usage":{"input_tokens":14106,"output_tokens":276,"thinking_tokens":275,"cache_read_tokens":0,"total_tokens":14382}}}
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
	void readsTheSameTerminalContractFromStreamJson() {
		ExecuteResult result = ExecuteResult.parseStreaming(STREAM, "", 0, Duration.ofSeconds(9));

		assertThat(result.isStructured()).isTrue();
		assertThat(result.getResponse()).isEqualTo("OK\n");
		assertThat(result.getConversationId()).isEqualTo("fc8e2caf-a03f-4fdb-b738-a49745a1207b");
		assertThat(result.getInputTokens()).isEqualTo(14106);
		assertThat(result.getRawOutput()).isEqualTo(STREAM);
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
	void successWithARefusedToolCallIsNotSuccess() throws IOException {
		String stream = fixture("success-with-refusal.jsonl");
		String stderr = "jetski: no output produced — a tool required the \"command\" permission "
				+ "that headless mode cannot prompt for, so it was auto-denied. Add an allow-rule under "
				+ "permissions.allow in settings.json (e.g. command(<target>)). Alternatively, re-run "
				+ "with --dangerously-skip-permissions to auto-approve all tools.";

		ExecuteResult result = ExecuteResult.parseStreaming(stream, stderr, 0, Duration.ofSeconds(10));

		assertThat(result.getStatus()).isEqualTo("SUCCESS");
		assertThat(result.getExitCode()).isZero();
		assertThat(result.isSoftDenied()).isTrue();
		assertThat(result.isSuccessful()).isFalse();
	}

	@Test
	void errorAfterRecoveryAndRealWorkStillCountsAsSuccess() throws IOException {
		ExecuteResult result = ExecuteResult.parseStreaming(fixture("error-after-recovery.jsonl"), "", 0,
				Duration.ofSeconds(18));

		assertThat(result.isReportedSuccessful()).isFalse();
		assertThat(result.getError()).contains("file:///workspace does not exist");
		assertThat(result.hasResponse()).isTrue();
		assertThat(result.hasUnrecoveredError()).isFalse();
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void errorWithoutLaterSuccessfulWorkIsNotSuccess() throws IOException {
		ExecuteResult result = ExecuteResult.parseStreaming(fixture("error-without-recovery.jsonl"), "", 0,
				Duration.ofSeconds(14));

		assertThat(result.getStatus()).isEqualTo("ERROR");
		assertThat(result.getExitCode()).isZero();
		assertThat(result.hasResponse()).isTrue();
		assertThat(result.isSoftDenied()).isFalse();
		assertThat(result.hasUnrecoveredError()).isTrue();
		assertThat(result.isSuccessful()).isFalse();
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

	private static String fixture(String name) throws IOException {
		try (InputStream stream = ExecuteResultTest.class.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IOException("Missing fixture " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
