/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagent;

import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import io.github.markpollack.agents.tck.PortableMcpAgentOptions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import io.github.markpollack.agents.sweagentsdk.transport.SweCliApi;
import io.github.markpollack.agents.sweagentsdk.transport.SweCliApi.SweResult;
import io.github.markpollack.agents.sweagentsdk.transport.SweCliApi.SweResultStatus;
import io.github.markpollack.agents.sweagentsdk.transport.SweCliApi.SweCliException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SweAgentModel.
 *
 * @author Mark Pollack
 */
class SweAgentModelTest {

	@Mock
	private SweCliApi mockSweCliApi;

	private SweAgentModel agentModel;

	private Path testWorkingDirectory;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		agentModel = new SweAgentModel(mockSweCliApi);
		testWorkingDirectory = Paths.get("/tmp/test-workspace");
	}

	@Test
	void testSuccessfulExecution() throws Exception {
		// Given
		String taskGoal = "Fix the bug in the authentication module";
		AgentTaskRequest request = new AgentTaskRequest(taskGoal, testWorkingDirectory, null);

		SweResult mockResult = new SweResult(SweResultStatus.SUCCESS,
				"Task completed successfully. Fixed authentication bug in auth.py", "", null);
		when(mockSweCliApi.execute(anyString(), any(Path.class), any())).thenReturn(mockResult);

		// When
		AgentResponse result = agentModel.call(request);

		// Then
		assertThat(result.getResults()).hasSize(1);
		assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
		assertThat(result.getResult().getOutput()).contains("Task completed successfully");
		assertThat(result.getMetadata().getDuration()).isNotNull();
	}

	@Test
	void testFailedExecution() throws Exception {
		// Given
		String taskGoal = "Implement a new feature";
		AgentTaskRequest request = new AgentTaskRequest(taskGoal, testWorkingDirectory, null);

		SweResult mockResult = new SweResult(SweResultStatus.ERROR, "",
				"Failed to implement feature: missing dependencies", null);
		when(mockSweCliApi.execute(anyString(), any(Path.class), any())).thenReturn(mockResult);

		// When
		AgentResponse result = agentModel.call(request);

		// Then
		assertThat(result.getResults()).hasSize(1);
		assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("ERROR");
		assertThat(result.getResult().getOutput()).contains("missing dependencies");
	}

	@Test
	void testExecutionWithOptions() throws Exception {
		// Given
		String taskGoal = "Refactor the codebase";
		SweAgentOptions options = SweAgentOptions.builder()
			.model("claude-3-5-sonnet")
			.timeout(Duration.ofMinutes(10))
			.maxIterations(15)
			.verbose(true)
			.build();
		AgentTaskRequest request = new AgentTaskRequest(taskGoal, testWorkingDirectory, options);

		SweResult mockResult = new SweResult(SweResultStatus.SUCCESS, "Refactoring completed", "", null);
		when(mockSweCliApi.execute(anyString(), any(Path.class), any())).thenReturn(mockResult);

		// When
		AgentResponse result = agentModel.call(request);

		// Then
		assertThat(result.getResults()).hasSize(1);
		assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
	}

	@Test
	void testCliException() throws Exception {
		// Given
		String taskGoal = "Test CLI exception handling";
		AgentTaskRequest request = new AgentTaskRequest(taskGoal, testWorkingDirectory, null);

		when(mockSweCliApi.execute(anyString(), any(Path.class), any()))
			.thenThrow(new SweCliException("CLI execution failed"));

		// When
		AgentResponse result = agentModel.call(request);

		// Then
		assertThat(result.getResults()).hasSize(1);
		assertThat(result.getResult().getMetadata().getFinishReason()).isEqualTo("ERROR");
		assertThat(result.getResult().getOutput()).contains("CLI execution failed");
	}

	@Test
	void testIsAvailable() {
		// Given
		when(mockSweCliApi.isAvailable()).thenReturn(true);

		// When
		boolean available = agentModel.isAvailable();

		// Then
		assertThat(available).isTrue();
	}

	@Test
	void testIsNotAvailable() {
		// Given
		when(mockSweCliApi.isAvailable()).thenReturn(false);

		// When
		boolean available = agentModel.isAvailable();

		// Then
		assertThat(available).isFalse();
	}

	@Test
	void testBuilderOptions() {
		// Test the builder pattern works correctly
		SweAgentOptions options = SweAgentOptions.builder()
			.model("gpt-4")
			.timeout(Duration.ofMinutes(15))
			.maxIterations(25)
			.verbose(false)
			.build();

		assertThat(options.getModel()).isEqualTo("gpt-4");
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(15));
		assertThat(options.getMaxIterations()).isEqualTo(25);
		assertThat(options.isVerbose()).isFalse();
	}

	private static final java.util.Map<String, McpServerDefinition> MCP_SERVERS = java.util.Map.of("files",
			new McpServerDefinition.StdioDefinition("npx", java.util.List.of("-y", "files-server"), java.util.Map.of()));

	@Test
	void refusesConfiguredMcpServersWithoutLaunchingSweAgent() throws Exception {
		AgentResponse response = agentModel.call(AgentTaskRequest.builder("Use the files server", testWorkingDirectory)
			.options(new PortableMcpAgentOptions(MCP_SERVERS))
			.build());
		assertThat(response.isSuccessful()).isFalse();
		assertThat(response.getText()).contains("files").contains("MCP").doesNotContain("Unexpected error");
		verify(mockSweCliApi, never()).execute(anyString(), any(Path.class), any());
	}

}
