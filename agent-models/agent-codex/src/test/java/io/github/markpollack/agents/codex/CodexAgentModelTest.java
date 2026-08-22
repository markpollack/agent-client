/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.ExecuteResult;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.codex.CodexPhaseCapture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodexAgentModelTest {

	@Test
	void publishesPhaseCaptureWithoutReplacingTerminalResult() {
		List<String> rollout = List.of(
				"{\"type\":\"session_meta\",\"payload\":{\"id\":\"session-1\",\"cli_version\":\"0.149.0\"}}",
				"{\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-5.4-mini\"}}",
				"{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"call_id\":\"call-1\",\"name\":\"apply_patch\",\"input\":\"*** Begin Patch\"}}",
				"{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"call_id\":\"call-1\",\"output\":\"Done\"}}",
				"{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"duration_ms\":1000,\"last_agent_message\":\"done\"}}");
		ExecuteResult result = new ExecuteResult("done", "session id: session-1", 0, Duration.ofSeconds(1),
				"gpt-5.4-mini", rollout);
		CodexClient client = mock(CodexClient.class);
		when(client.execute(anyString(), any(ExecuteOptions.class))).thenReturn(result);
		CodexAgentModel model = new CodexAgentModel(client, CodexAgentOptions.builder().build(), null);

		AgentResponse response = model.call(AgentTaskRequest.builder("inspect", Path.of(".")).build());
		CodexPhaseCapture capture = response.getMetadata().get("phaseCapture");

		assertThat(response.getResult().getOutput()).isEqualTo("done");
		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).hasSize(1);
	}

}
