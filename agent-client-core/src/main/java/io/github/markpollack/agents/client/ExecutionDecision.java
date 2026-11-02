/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Instant;
import java.util.Optional;

/** One immutable, runtime-elected outcome. */
public final class ExecutionDecision {

	private final String decisionId;

	private final String executionId;

	private final String sessionId;

	private final Optional<String> connectionId;

	private final Instant acceptedAt;

	private final ExecutionOutcome outcome;

	private final ProviderObservation provider;

	private final ApplicationCorrelation correlation;

	ExecutionDecision(DefaultAgentExecution execution, ExecutionOutcome outcome, ProviderObservation provider,
			Instant at) {
		this.decisionId = java.util.UUID.randomUUID().toString();
		this.executionId = execution.executionId();
		this.sessionId = execution.sessionId();
		this.connectionId = Optional.ofNullable(execution.store.session.connectionId);
		this.acceptedAt = at;
		this.outcome = outcome;
		this.provider = provider;
		this.correlation = execution.correlation();
	}

	public String decisionId() {
		return this.decisionId;
	}

	public String executionId() {
		return this.executionId;
	}

	public String sessionId() {
		return this.sessionId;
	}

	public Optional<String> connectionId() {
		return this.connectionId;
	}

	public Instant acceptedAt() {
		return this.acceptedAt;
	}

	public ExecutionOutcome outcome() {
		return this.outcome;
	}

	public ProviderObservation provider() {
		return this.provider;
	}

	public ApplicationCorrelation correlation() {
		return this.correlation;
	}

}
