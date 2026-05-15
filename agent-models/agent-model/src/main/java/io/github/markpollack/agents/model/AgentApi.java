/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.model;

/**
 * Core interface for autonomous agent execution. Sends a task request to a CLI agent and
 * returns the result synchronously.
 *
 * <p>
 * This is the primary interface for agent interaction. Implementations wrap specific CLI
 * tools (Claude Code, Codex, Gemini, etc.) and translate task requests into CLI
 * invocations.
 *
 * <p>
 * As a functional interface, it can be used with lambdas: <pre>{@code
 * AgentApi agent = request -> myClient.execute(request);
 * AgentResponse response = agent.call(request);
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.16.0
 * @see AgentModel
 */
@FunctionalInterface
public interface AgentApi {

	/**
	 * Execute a development task using the agent. This is a blocking operation that waits
	 * for the agent to complete the task.
	 * @param request the task request containing goal, workspace, and constraints
	 * @return the result of the agent execution
	 */
	AgentResponse call(AgentTaskRequest request);

	/**
	 * Check if the agent is available and ready to accept tasks. Implementations may
	 * override this to perform actual availability checks.
	 * @return true if the agent is available, false otherwise
	 */
	default boolean isAvailable() {
		return true;
	}

}
