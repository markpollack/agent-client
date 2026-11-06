/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.ExecutionRecorder;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;

/** A recorder that takes generations and keeps what the provider sealed. */
final class GenerationProbe implements ExecutionRecorder {

	final CompletableFuture<Optional<NativeGeneration>> sealed = new CompletableFuture<>();

	@Override
	public Supplier<String> prepare(ExecutionDecision decision) {
		return () -> "probe-" + decision.executionId();
	}

	@Override
	public boolean takesNativeGeneration() {
		return true;
	}

	@Override
	public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
		this.sealed.complete(generation);
		return new Prepared(prepare(decision), new NativeRecording(NativeRecording.Outcome.NOT_CAPTURED,
				generation.map(NativeGeneration::acquisitionProfile), "probe", "probe"));
	}

}
