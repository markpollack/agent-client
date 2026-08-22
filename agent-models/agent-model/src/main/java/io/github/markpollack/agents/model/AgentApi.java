/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
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
 * <p>
 * This low-level SPI returns the provider response, but it does not expose the trajectory
 * convenience API. Consumers that need a provider's parsed trajectory should invoke
 * through the {@code AgentClient} facade in {@code agent-client-core} and read it from
 * {@code AgentClientResponse.getPhaseCapture()}. Capture is produced on every supported
 * provider call and is not gated by raw trace-file configuration.
 *
 * @author Mark Pollack
 * @since 0.16.0
 * @see AgentModel
 */
@FunctionalInterface
public interface AgentApi {

	/**
	 * Execute a development task using the agent. This is a blocking operation that waits
	 * for the agent to complete the task. Prefer the {@code AgentClient} facade when the
	 * parsed execution trajectory is required; this SPI has no trajectory accessor.
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
