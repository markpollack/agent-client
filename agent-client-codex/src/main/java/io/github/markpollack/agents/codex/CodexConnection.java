/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * One-shot: exactly one generation. Retained: each turn is a replacement process that
 * resumes the exact thread identity emitted by the first dispatched turn. Any turn that
 * does not end with a parsed successful terminal quarantines the thread; close is the
 * only exit.
 */
final class CodexConnection implements AgentExecutionProvider.Connection {

	private final CodexConfiguration configuration;

	private final AgentOptions options;

	private final boolean retained;

	private final List<CodexGeneration> generations = new ArrayList<>();

	private volatile CodexGeneration current;

	private final CodexThread thread = new CodexThread();

	private boolean closed;

	CodexConnection(CodexConfiguration configuration, AgentOptions options, boolean retained) {
		this.configuration = configuration;
		this.options = options;
		this.retained = retained;
		this.current = new CodexGeneration(configuration, options, null, 0);
		this.generations.add(this.current);
	}

	@Override
	public synchronized AgentExecutionProvider.Turn prepare(String executionId) {
		checkReady();
		var generation = this.current.prepared ? replacement(this.current) : this.current;
		generation.prepared = true;
		return new CodexTurn(generation, executionId, this.thread);
	}

	private void checkReady() {
		if (this.closed)
			throw new IllegalStateException("Connection is closed");
		var quarantine = this.thread.quarantined();
		if (quarantine.isPresent())
			throw new IllegalStateException("Codex thread is quarantined: " + quarantine.get());
	}

	/**
	 * A replacement process resumes the exact emitted thread; it never replays a prompt.
	 */
	private CodexGeneration replacement(CodexGeneration previous) {
		if (!this.retained)
			throw new IllegalStateException("One-shot connection cannot replay");
		String nativeId = this.thread.nativeId().orElse(null);
		if (previous.process.dispatched() && nativeId == null)
			throw new IllegalStateException("Cannot resume a dispatched Codex turn without emitted thread identity");
		var generation = new CodexGeneration(this.configuration, this.options, nativeId,
				nativeId == null ? 0 : this.generations.size());
		this.generations.add(generation);
		this.current = generation;
		return generation;
	}

	@Override
	public Optional<String> connectionId() {
		return Optional.of(this.current.id);
	}

	@Override
	public Optional<ProviderReference> providerReference() {
		return this.thread.nativeId()
			.map(n -> new ProviderReference("codex", this.current.options.getWorkingDirectory().toString(), n));
	}

	@Override
	public void close() {
		List<CodexGeneration> owned;
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
