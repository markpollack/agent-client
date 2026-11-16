/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.ExecutionRecorder;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;

/**
 * A recorder that delegates to the shipped one and keeps what it was given and what it
 * decided. A synchronous {@code run()} returns only the response, so a test reads the
 * decision and the recording here.
 */
final class CapturingRecorder implements ExecutionRecorder {

	final CompletableFuture<ExecutionDecision> decision = new CompletableFuture<>();

	final CompletableFuture<Optional<NativeGeneration>> generation = new CompletableFuture<>();

	final CompletableFuture<NativeRecording> recording = new CompletableFuture<>();

	private final ExecutionRecorder delegate;

	CapturingRecorder(ExecutionRecorder delegate) {
		this.delegate = delegate;
	}

	@Override
	public Supplier<String> prepare(ExecutionDecision decision) {
		return this.delegate.prepare(decision);
	}

	@Override
	public boolean takesNativeGeneration() {
		return this.delegate.takesNativeGeneration();
	}

	@Override
	public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
		this.decision.complete(decision);
		this.generation.complete(generation);
		var prepared = this.delegate.prepareRecording(decision, generation);
		this.recording.complete(prepared.nativeRecording());
		return prepared;
	}

}
