/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;

final class AntigravityConnection implements AgentExecutionProvider.Connection {

	private final AntigravityGeneration generation;

	private boolean prepared;

	private boolean closed;

	AntigravityConnection(AntigravityConfiguration configuration, AgentOptions options) {
		this.generation = new AntigravityGeneration(configuration, options);
	}

	@Override
	public synchronized AgentExecutionProvider.Turn prepare(String executionId) {
		if (this.closed || this.prepared)
			throw new IllegalStateException("One-shot connection cannot replay");
		this.prepared = true;
		return new AntigravityTurn(this.generation, executionId);
	}

	@Override
	public Optional<String> connectionId() {
		return Optional.of(this.generation.id);
	}

	@Override
	public Optional<ProviderReference> providerReference() {
		var n = this.generation.nativeId;
		return n == null ? Optional.empty() : Optional
			.of(new ProviderReference("antigravity", this.generation.options.getWorkingDirectory().toString(), n));
	}

	@Override
	public void close() {
		synchronized (this) {
			this.closed = true;
		}
		this.generation.close();
	}

}
