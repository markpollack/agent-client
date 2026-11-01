/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClaudeExecutionResultTest {

	@Test
	void nativeNullUsageRemainsUnavailableAndDoesNotBreakResultExposure() {
		Map<String, Object> usage = new HashMap<>();
		usage.put("input_tokens", 1);
		usage.put("fallback_credit", null);
		var terminal = ResultMessage.builder().sessionId("session").result("ok").usage(usage).build();
		var result = ClaudeExecutionResult
			.read(List.<ParsedMessage>of(ParsedMessage.RegularMessage.of(terminal)).iterator(), null);
		var response = result.response("model", Duration.ofSeconds(1), null, null);
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
		Map<String, Object> reported = response.getMetadata().get("reportedUsage");
		assertThat(reported).containsEntry("fallback_credit", null).containsEntry("input_tokens", 1);
		assertThat(response.getMetadata().<Object>get("totalCostUsd")).isNull();
		assertThatThrownBy(() -> reported.put("new", 1)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void preservesStructuredOutputAndReportedError() {
		var result = new ClaudeExecutionResult();
		result.accept(ParsedMessage.RegularMessage.of(ResultMessage.builder()
			.sessionId("session")
			.structuredOutput(Map.of("answer", 42))
			.isError(true)
			.totalCostUsd(0.12)
			.build()));
		var response = result.response("model", Duration.ZERO, null, null);
		assertThat(response.getResult().getOutput()).isEqualTo("{\"answer\":42}");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("ERROR");
		assertThat(response.getMetadata().<Double>get("totalCostUsd")).isEqualTo(0.12);
	}

	@Test
	void missingTerminalAndConflictingSessionCannotBecomeSuccess() {
		var result = new ClaudeExecutionResult();
		result.accept(ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(new TextBlock("partial")))));
		assertThat(result.response("model", Duration.ZERO, null, null).getResult().getMetadata().getFinishReason())
			.isEqualTo("ERROR");
		result.accept(ParsedMessage.RegularMessage.of(SystemMessage.of("init", Map.of("session_id", "first"))));
		assertThatThrownBy(() -> result
			.accept(ParsedMessage.RegularMessage.of(ResultMessage.builder().sessionId("other").build())))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Conflicting");
	}

}
