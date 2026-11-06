/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Prepare exact immutable delivery bytes once, outside the result/admission path. */
public interface ExecutionRecorder extends Function<ExecutionDecision, String> {

	Supplier<String> prepare(ExecutionDecision decision);

	/** Provider source preparation is optional and independent of the accepted result. */
	default Supplier<String> prepare(ExecutionDecision decision, java.util.Map<String, Object> evidence) {
		return prepare(decision);
	}

	/**
	 * Whether this recorder takes the typed {@link NativeGeneration}. Client then calls
	 * {@link #prepareRecording(ExecutionDecision, Optional)} instead of the evidence-map
	 * method. Recorders that do not opt in keep the map until it is removed with its
	 * callers.
	 */
	default boolean takesNativeGeneration() {
		return false;
	}

	/**
	 * Prepare the initial binding from the provider's sealed one-shot generation, if any.
	 * Invoked once on Client's recording lane after acceptance. Native qualification
	 * never fails the delivery: an unqualified or refused generation still yields an
	 * evidence-only binding, and the returned {@link NativeRecording} says which.
	 */
	default Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
		return new Prepared(prepare(decision), new NativeRecording(NativeRecording.Outcome.NOT_CAPTURED,
				Optional.empty(), "", "This recorder does not take native generations"));
	}

	/** Exact delivery bytes, frozen, plus how native sources were treated. */
	record Prepared(Supplier<String> delivery, NativeRecording nativeRecording) {

		public Prepared {
			java.util.Objects.requireNonNull(delivery, "delivery");
			java.util.Objects.requireNonNull(nativeRecording, "nativeRecording");
		}

	}

	@Override
	default String apply(ExecutionDecision decision) {
		return prepare(decision).get();
	}

}
