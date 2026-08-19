/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.advisor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.github.markpollack.agents.client.AgentClientRequest;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.Goal;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisorChain;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AgentModelCallAdvisor}.
 *
 * @author Mark Pollack
 */
class AgentModelCallAdvisorTests {

	@Test
	void shouldCallAgentModelWithCorrectRequest() {
		AgentModel agentModel = mock(AgentModel.class);
		ArgumentCaptor<AgentTaskRequest> taskRequestCaptor = ArgumentCaptor.forClass(AgentTaskRequest.class);
		AgentResponse mockResponse = new AgentResponse(List.of(mock(AgentGeneration.class)));
		given(agentModel.call(taskRequestCaptor.capture())).willReturn(mockResponse);

		AgentModelCallAdvisor advisor = new AgentModelCallAdvisor(agentModel);

		Goal goal = new Goal("Test goal");
		Path workingDir = Path.of("/test/dir");
		AgentOptions options = mock(AgentOptions.class);
		AgentClientRequest request = new AgentClientRequest(goal, workingDir, options, new HashMap<>());

		AgentCallAdvisorChain mockChain = mock(AgentCallAdvisorChain.class);
		AgentClientResponse response = advisor.adviseCall(request, mockChain);

		// Verify the agent model was called
		verify(agentModel).call(any(AgentTaskRequest.class));

		// Verify the task request was constructed correctly
		AgentTaskRequest taskRequest = taskRequestCaptor.getValue();
		assertThat(taskRequest.goal()).isEqualTo("Test goal");
		assertThat(taskRequest.workingDirectory()).isEqualTo(workingDir);
		assertThat(taskRequest.options()).isEqualTo(options);

		// Verify the response wraps the agent response
		assertThat(response.agentResponse()).isEqualTo(mockResponse);
	}

	@Test
	void shouldPreserveContextFromRequest() {
		AgentModel agentModel = mock(AgentModel.class);
		AgentResponse mockResponse = new AgentResponse(List.of(mock(AgentGeneration.class)));
		given(agentModel.call(any(AgentTaskRequest.class))).willReturn(mockResponse);

		AgentModelCallAdvisor advisor = new AgentModelCallAdvisor(agentModel);

		Goal goal = new Goal("Test goal");
		Path workingDir = Path.of("/test/dir");
		AgentOptions options = mock(AgentOptions.class);
		HashMap<String, Object> context = new HashMap<>();
		context.put("test-key", "test-value");
		AgentClientRequest request = new AgentClientRequest(goal, workingDir, options, context);

		AgentCallAdvisorChain mockChain = mock(AgentCallAdvisorChain.class);
		AgentClientResponse response = advisor.adviseCall(request, mockChain);

		// Verify context is preserved
		assertThat(response.context()).containsEntry("test-key", "test-value");
	}

	@Test
	void shouldHaveLowestPrecedence() {
		AgentModel agentModel = mock(AgentModel.class);
		AgentModelCallAdvisor advisor = new AgentModelCallAdvisor(agentModel);
		assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void shouldHaveName() {
		AgentModel agentModel = mock(AgentModel.class);
		AgentModelCallAdvisor advisor = new AgentModelCallAdvisor(agentModel);
		assertThat(advisor.getName()).isEqualTo(AgentModelCallAdvisor.class.getName());
	}

}
