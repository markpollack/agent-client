/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.util.*;
import java.util.function.Consumer;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Registered before SDK acquisition; all generations remain owned through uncertainty.
 */
final class ClaudeClientConnection implements AgentExecutionProvider.Connection {

	private final ClaudeClientConfiguration config;

	private final boolean retained;

	private final List<ClaudeClientGeneration> generations = new ArrayList<>();

	private volatile AgentOptions options;

	private volatile ClaudeClientGeneration current;

	private volatile String nativeId;

	private boolean closed;

	ClaudeClientConnection(ClaudeClientConfiguration config, AgentOptions options, boolean retained) {
		this.config = config;
		this.options = options;
		this.retained = retained;
	}

	void connect() {
		ClaudeClientGeneration generation;
		synchronized (this) {
			if (this.closed)
				throw new IllegalStateException("Connection is closed");
			generation = new ClaudeClientGeneration(this.config, this.options, this.nativeId, this.retained);
			this.generations.add(generation);
			this.current = generation;
		}
		generation.connect();
		synchronized (this) {
			if (!this.closed)
				return;
		}
		generation.close();
		throw new IllegalStateException("Connection closed during setup");
	}

	@Override
	public AgentExecutionProvider.Turn prepare(String executionId) {
		return prepare(executionId, event -> {
		});
	}

	@Override
	public AgentExecutionProvider.Turn prepare(String executionId, Consumer<Map<String, Object>> progress) {
		var generation = this.current;
		if (!generation.client.isConnected()) {
			generation.close();
			if (!this.retained)
				throw new IllegalStateException("One-shot connection cannot create a replacement generation");
			if (generation.dispatched && this.nativeId == null)
				throw new IllegalStateException("Cannot reconnect dispatched conversation without native identity");
			connect();
			generation = this.current;
		}
		synchronized (this) {
			if (this.closed)
				throw new IllegalStateException("Connection is closed");
			return new ClaudeClientTurn(generation, executionId, this.retained, this::observeIdentity, progress);
		}
	}

	private synchronized void observeIdentity(String id) {
		if (id == null || id.isBlank())
			return;
		if (this.nativeId != null && !this.nativeId.equals(id))
			throw new IllegalStateException("Native history identity changed");
		this.nativeId = id;
	}

	@Override
	public Optional<ProviderReference> providerReference() {
		var id = this.nativeId;
		return id == null ? Optional.empty()
				: Optional.of(new ProviderReference("claude", this.config.directory(this.options).toString(), id));
	}

	@Override
	public Optional<String> connectionId() {
		var generation = this.current;
		return generation == null ? Optional.empty() : Optional.of(generation.id);
	}

	@Override
	public void refresh(AgentOptions replacement) {
		this.current.close();
		synchronized (this) {
			if (this.closed)
				throw new IllegalStateException("Connection is closed");
			if (this.current.dispatched && this.nativeId == null)
				throw new IllegalStateException("Cannot refresh without exact history identity");
			this.options = replacement;
		}
		connect();
	}

	@Override
	public void close() {
		List<ClaudeClientGeneration> owned;
		synchronized (this) {
			this.closed = true;
			owned = List.copyOf(this.generations);
		}
		RuntimeException failure = null;
		for (var generation : owned) {
			try {
				generation.close();
			}
			catch (RuntimeException ex) {
				if (failure == null)
					failure = ex;
				else
					failure.addSuppressed(ex);
			}
		}
		if (failure != null)
			throw failure;
	}

}
