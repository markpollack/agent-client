/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.types;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses the envelope Grok actually emits.
 *
 * <p>
 * The fixture is a verbatim capture from grok 1.0.5, not a guess from the documentation —
 * the published flag reference describes {@code --output-format json} without stating the
 * envelope's shape, and the shape is what a parser depends on.
 */
class ExecuteResultTest {

	private static final String ENVELOPE = """
			{
			  "text": "OK",
			  "stopReason": "end_turn",
			  "sessionId": "01a020d9-e9cf-7da1-b70e-a6ef6781c75d",
			  "requestId": "5bd4e075-5463-422f-bf78-76313a95622e",
			  "thought": "The user wants me to reply with the single word OK.",
			  "usage": {
			    "input_tokens": 13115,
			    "cache_read_input_tokens": 2944,
			    "cache_creation_input_tokens": 0,
			    "output_tokens": 27,
			    "reasoning_tokens": 22,
			    "total_tokens": 16086
			  },
			  "num_turns": 1,
			  "total_cost_usd": 0.00473688,
			  "total_cost_usd_ticks": 47368800,
			  "modelUsage": {
			    "grok-4.6-build": {
			      "inputTokens": 13115,
			      "outputTokens": 27,
			      "modelCalls": 1,
			      "costUSD": 0.00473688
			    }
			  }
			}
			""";

	@Test
	void readsTextSessionAndUsageFromTheEnvelope() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, 0, Duration.ofSeconds(9));

		assertThat(result.isStructured()).isTrue();
		assertThat(result.getText()).isEqualTo("OK");
		assertThat(result.getSessionId()).isEqualTo("01a020d9-e9cf-7da1-b70e-a6ef6781c75d");
		assertThat(result.getStopReason()).isEqualTo("end_turn");
		assertThat(result.getInputTokens()).isEqualTo(13115);
		assertThat(result.getOutputTokens()).isEqualTo(27);
		assertThat(result.getReasoningTokens()).isEqualTo(22);
		assertThat(result.getCacheReadInputTokens()).isEqualTo(2944);
		assertThat(result.getTotalTokens()).isEqualTo(16086);
		assertThat(result.getNumTurns()).isEqualTo(1);
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void reportsARealCostRatherThanRequiringAPriceTable() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, 0, Duration.ofSeconds(9));

		assertThat(result.getTotalCostUsd()).isEqualTo(0.00473688);
	}

	@Test
	void namesTheModelThatActuallyRan() {
		ExecuteResult result = ExecuteResult.parse(ENVELOPE, 0, Duration.ofSeconds(9));

		// The envelope has no top-level model field; modelUsage is keyed by the resolved
		// model, which is the honest answer when the CLI expands an alias.
		assertThat(result.getModel()).isEqualTo("grok-4.6-build");
	}

	@Test
	void keepsUnparseableOutputVerbatimInsteadOfDiscardingIt() {
		ExecuteResult result = ExecuteResult.parse("error: not authenticated", 1, Duration.ofSeconds(1));

		assertThat(result.isStructured()).isFalse();
		assertThat(result.getText()).isEqualTo("error: not authenticated");
		assertThat(result.isSuccessful()).isFalse();
	}

	@Test
	void anEmptyRunIsReportedAsEmptyRatherThanFailingToParse() {
		ExecuteResult result = ExecuteResult.parse("", 0, Duration.ofSeconds(1));

		assertThat(result.isStructured()).isFalse();
		assertThat(result.getText()).isEmpty();
	}

}
