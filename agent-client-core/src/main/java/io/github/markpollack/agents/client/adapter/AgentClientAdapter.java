/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.adapter;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;

import java.nio.file.Path;
import java.util.function.BiFunction;

/**
 * Adapter that bridges {@link AgentClient} to the spring-ai-judge JudgeAgentClient
 * interface.
 *
 * <p>
 * This adapter allows you to use an AgentClient with the spring-ai-judge library's
 * AgentJudge without creating a hard dependency on spring-ai-judge.
 * </p>
 *
 * <p>
 * Example usage with spring-ai-judge:
 * </p>
 *
 * <pre>{@code
 * // Create the adapter
 * AgentClientAdapter adapter = new AgentClientAdapter(agentClient);
 *
 * // Use with spring-ai-judge's AgentJudge
 * JudgeAgentClient judgeClient = adapter::execute;
 *
 * AgentJudge judge = AgentJudge.builder()
 *     .agentClient(judgeClient)
 *     .criteria("Evaluate code quality")
 *     .build();
 * }</pre>
 *
 * <p>
 * Or using the functional interface directly:
 * </p>
 *
 * <pre>{@code
 * AgentJudge judge = AgentJudge.builder()
 *     .agentClient((goal, workspace) -> {
 *         AgentClientResponse response = agentClient.goal(goal)
 *             .workingDirectory(workspace)
 *             .run();
 *         return new JudgeAgentResponse(response.getResult(), response.isSuccessful());
 *     })
 *     .criteria("Evaluate code quality")
 *     .build();
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public class AgentClientAdapter implements BiFunction<String, Path, AgentClientAdapterResponse> {

	private final AgentClient agentClient;

	/**
	 * Create an adapter for the given AgentClient.
	 * @param agentClient the agent client to adapt
	 */
	public AgentClientAdapter(AgentClient agentClient) {
		if (agentClient == null) {
			throw new IllegalArgumentException("AgentClient cannot be null");
		}
		this.agentClient = agentClient;
	}

	/**
	 * Execute an agent task with the given goal and workspace.
	 * <p>
	 * This method can be used as a method reference to satisfy the JudgeAgentClient
	 * functional interface from spring-ai-judge.
	 * </p>
	 * @param goal the goal/task for the agent to accomplish
	 * @param workspace the working directory for agent execution
	 * @return the adapter response containing result and success status
	 */
	public AgentClientAdapterResponse execute(String goal, Path workspace) {
		AgentClientResponse response = agentClient.goal(goal).workingDirectory(workspace).run();
		return new AgentClientAdapterResponse(response.getResult(), response.isSuccessful());
	}

	@Override
	public AgentClientAdapterResponse apply(String goal, Path workspace) {
		return execute(goal, workspace);
	}

	/**
	 * Get the underlying AgentClient.
	 * @return the agent client
	 */
	public AgentClient getAgentClient() {
		return agentClient;
	}

}
