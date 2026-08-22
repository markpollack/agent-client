/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.ExecuteResult;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrokAgentModelTest {

	@Test
	void publishesPhaseCaptureWithoutReplacingTerminalResult() {
		String stream = """
				{"type":"text","data":"done"}
				{"type":"tool_call","toolCallId":"call-1","toolName":"Read","rawInput":{"path":"README.md"}}
				{"type":"tool_call_update","toolCallId":"call-1","status":"completed","rawOutput":{"text":"ok"}}
				{"type":"end","stopReason":"end_turn","sessionId":"session-1","usage":{"input_tokens":10,"output_tokens":2},"num_turns":1,"modelUsage":{"grok-4.6":{}}}
				""";
		ExecuteResult result = ExecuteResult.parseStreaming(stream, 0, Duration.ofSeconds(1));
		GrokClient client = mock(GrokClient.class);
		when(client.execute(anyString(), any(ExecuteOptions.class))).thenReturn(result);
		GrokAgentModel model = new GrokAgentModel(client, GrokAgentOptions.builder().build());

		AgentResponse response = model.call(AgentTaskRequest.builder("inspect", Path.of(".")).build());
		GrokPhaseCapture capture = response.getMetadata().get("phaseCapture");

		assertThat(response.getResult().getOutput()).isEqualTo("done");
		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).hasSize(1);
	}

}
