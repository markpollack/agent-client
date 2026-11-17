/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.ApplicationCorrelation;
import io.github.markpollack.workflow.flows.StepContext;

/**
 * Test-only adapter from Agent Workflow's {@code AgentClient} step interface to Agent
 * Client: each Workflow call is one synchronous {@code run()}, correlated with the
 * Workflow run, invocation and attempt that the runtime gives the step. Not a published
 * API: Workflow bans a dependency on Agent Client, so the adapter lives with the
 * consumer.
 */
final class WorkflowAgentClient implements io.github.markpollack.workflow.flows.steps.AgentClient {

	private final io.github.markpollack.agents.client.AgentClient client;

	final AtomicReference<AgentClientResponse> lastResponse = new AtomicReference<>();

	final AtomicReference<StepContext> lastContext = new AtomicReference<>();

	WorkflowAgentClient(io.github.markpollack.agents.client.AgentClient client) {
		this.client = Objects.requireNonNull(client, "client");
	}

	@Override
	public String execute(String prompt, StepContext context) {
		return executeForResult(prompt, context).text();
	}

	@Override
	public ExecutionResult executeForResult(String prompt, StepContext context) {
		var correlation = new ApplicationCorrelation(Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.of(context.runId()), Optional.of(context.invocationId()), Optional.of(context.attemptId()));
		var response = this.client.goal(prompt).correlation(correlation).run();
		this.lastContext.set(context);
		this.lastResponse.set(response);
		if (!response.isSuccessful())
			throw new IllegalStateException("Agent Client run was not successful: " + response.getResult());
		return new ExecutionResult(response.getResult(), null);
	}

}
