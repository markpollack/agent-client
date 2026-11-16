/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.ProviderReference;
import io.github.markpollack.agents.model.AgentOptions;

/** One fresh Junie execution; the ACP process lives only for its turn. */
final class JunieConnection implements AgentExecutionProvider.Connection {

	final String id = UUID.randomUUID().toString();

	final JunieConfiguration configuration;

	final Path workingDirectory;

	private JunieTurn turn;

	private boolean closed;

	JunieConnection(JunieConfiguration configuration, AgentOptions options) {
		this.configuration = configuration;
		this.workingDirectory = configuration.workingDirectory(options);
	}

	@Override
	public synchronized AgentExecutionProvider.Turn prepare(String executionId) {
		if (this.closed || this.turn != null)
			throw new IllegalStateException("One-shot Junie connection cannot replay");
		this.turn = new JunieTurn(this, executionId);
		return this.turn;
	}

	@Override
	public Optional<String> connectionId() {
		return Optional.of(this.id);
	}

	@Override
	public synchronized Optional<ProviderReference> providerReference() {
		String session = this.turn == null ? null : this.turn.observation().acpSessionId();
		return session == null ? Optional.empty()
				: Optional.of(new ProviderReference("junie", this.workingDirectory.toString(), session));
	}

	@Override
	public void close() {
		JunieTurn current;
		synchronized (this) {
			this.closed = true;
			current = this.turn;
		}
		if (current != null)
			current.stop(true);
	}

}
