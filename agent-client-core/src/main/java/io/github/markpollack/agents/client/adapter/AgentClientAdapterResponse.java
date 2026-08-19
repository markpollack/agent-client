/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.adapter;

/**
 * Response from an AgentClientAdapter execution.
 *
 * <p>
 * This record provides a simple container for agent execution results that can be easily
 * adapted to other interfaces like spring-ai-judge's JudgeAgentResponse.
 * </p>
 *
 * @param result the result/output from the agent execution
 * @param successful whether the agent execution completed successfully
 * @author Mark Pollack
 * @since 0.1.0
 */
public record AgentClientAdapterResponse(String result, boolean successful) {

	/**
	 * Create a successful response with the given result.
	 * @param result the result of the successful execution
	 * @return a new AgentClientAdapterResponse marked as successful
	 */
	public static AgentClientAdapterResponse success(String result) {
		return new AgentClientAdapterResponse(result, true);
	}

	/**
	 * Create a failed response with the given result/error message.
	 * @param result the error message or partial result
	 * @return a new AgentClientAdapterResponse marked as failed
	 */
	public static AgentClientAdapterResponse failure(String result) {
		return new AgentClientAdapterResponse(result, false);
	}

}
