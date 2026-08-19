/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.alibaba.qwen.code.cli.protocol.data.PermissionMode;
import com.alibaba.qwen.code.cli.transport.TransportOptions;
import org.junit.jupiter.api.Test;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

import static org.assertj.core.api.Assertions.assertThat;

class QwenCodeAgentModelTest {

	@Test
	void callShouldReturnSuccessResponse() {
		QwenCodeQueryFunction mockQuery = (prompt, options) -> List.of("Hello from Qwen Code!");

		QwenCodeAgentModel model = new QwenCodeAgentModel(QwenCodeAgentOptions.builder().build(), mockQuery);

		AgentTaskRequest request = AgentTaskRequest.builder("Say hello", Path.of("/tmp")).build();
		AgentResponse response = model.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getText()).isEqualTo("Hello from Qwen Code!");
		assertThat(response.isSuccessful()).isTrue();
	}

	@Test
	void callShouldJoinMultipleResults() {
		QwenCodeQueryFunction mockQuery = (prompt, options) -> List.of("Line 1", "Line 2", "Line 3");

		QwenCodeAgentModel model = new QwenCodeAgentModel(QwenCodeAgentOptions.builder().build(), mockQuery);

		AgentTaskRequest request = AgentTaskRequest.builder("Multi-line task", Path.of("/tmp")).build();
		AgentResponse response = model.call(request);

		assertThat(response.getText()).isEqualTo("Line 1\nLine 2\nLine 3");
	}

	@Test
	void callShouldReturnErrorResponseOnException() {
		QwenCodeQueryFunction failingQuery = (prompt, options) -> {
			throw new RuntimeException("CLI not found");
		};

		QwenCodeAgentModel model = new QwenCodeAgentModel(QwenCodeAgentOptions.builder().build(), failingQuery);

		AgentTaskRequest request = AgentTaskRequest.builder("Failing task", Path.of("/tmp")).build();
		AgentResponse response = model.call(request);

		assertThat(response).isNotNull();
		assertThat(response.isSuccessful()).isFalse();
		assertThat(response.getText()).contains("CLI not found");
	}

	@Test
	void callShouldPassTransportOptions() {
		TransportOptions[] captured = new TransportOptions[1];
		QwenCodeQueryFunction capturingQuery = (prompt, options) -> {
			captured[0] = options;
			return List.of("ok");
		};

		QwenCodeAgentOptions agentOptions = QwenCodeAgentOptions.builder()
			.model("qwen3-coder-flash")
			.timeout(Duration.ofMinutes(3))
			.yolo(true)
			.executablePath("/opt/qwen")
			.build();

		QwenCodeAgentModel model = new QwenCodeAgentModel(agentOptions, capturingQuery);
		AgentTaskRequest request = AgentTaskRequest.builder("test", Path.of("/workspace")).build();
		model.call(request);

		assertThat(captured[0]).isNotNull();
		assertThat(captured[0].getModel()).isEqualTo("qwen3-coder-flash");
		assertThat(captured[0].getPermissionMode()).isEqualTo(PermissionMode.YOLO);
		assertThat(captured[0].getPathToQwenExecutable()).isEqualTo("/opt/qwen");
		assertThat(captured[0].getCwd()).isEqualTo("/workspace");
	}

	@Test
	void callShouldMergeRequestOptions() {
		TransportOptions[] captured = new TransportOptions[1];
		QwenCodeQueryFunction capturingQuery = (prompt, options) -> {
			captured[0] = options;
			return List.of("ok");
		};

		QwenCodeAgentOptions defaults = QwenCodeAgentOptions.builder().model("qwen3-coder").yolo(true).build();

		QwenCodeAgentOptions requestOptions = QwenCodeAgentOptions.builder()
			.model("qwen3-coder-flash")
			.yolo(false)
			.permissionMode(PermissionMode.PLAN)
			.build();

		QwenCodeAgentModel model = new QwenCodeAgentModel(defaults, capturingQuery);
		AgentTaskRequest request = AgentTaskRequest.builder("test", Path.of("/tmp")).options(requestOptions).build();
		model.call(request);

		assertThat(captured[0].getModel()).isEqualTo("qwen3-coder-flash");
		assertThat(captured[0].getPermissionMode()).isEqualTo(PermissionMode.PLAN);
	}

}
