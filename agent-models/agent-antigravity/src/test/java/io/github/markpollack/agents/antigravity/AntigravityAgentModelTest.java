/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity;

import java.nio.file.Path;
import java.time.Duration;

import io.github.markpollack.agents.antigravitysdk.AntigravityClient;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteResult;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.antigravity.AntigravityPhaseCapture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AntigravityAgentModelTest {

	@Test
	void publishesPhaseCaptureWithoutReplacingTerminalResult() {
		String stream = """
				{"event":"init","conversation_id":"conversation-1","init":{"model":"gemini-3.1-pro-high"}}
				{"event":"step_update","step_update":{"step_type":"tool","step_index":0,"state":"SUCCESS","tool_info":{"name":"run_command","parameters":{"command":"pwd"},"output":"/tmp/work"}}}
				{"event":"result","result":{"conversation_id":"conversation-1","status":"SUCCESS","response":"done","duration_seconds":1.0,"num_turns":1,"usage":{"input_tokens":10,"output_tokens":2}}}
				""";
		ExecuteResult result = ExecuteResult.parseStreaming(stream, "", 0, Duration.ofSeconds(1));
		AntigravityClient client = mock(AntigravityClient.class);
		when(client.execute(anyString(), any(ExecuteOptions.class))).thenReturn(result);
		AntigravityAgentModel model = new AntigravityAgentModel(client, AntigravityAgentOptions.builder().build());

		AgentResponse response = model.call(AgentTaskRequest.builder("inspect", Path.of(".")).build());
		AntigravityPhaseCapture capture = response.getMetadata().get("phaseCapture");

		assertThat(response.getResult().getOutput()).isEqualTo("done");
		assertThat(capture).isNotNull();
		assertThat(capture.toolUses()).hasSize(1);
	}

}
