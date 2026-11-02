/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.function.Function;
import java.util.function.Supplier;

/** Prepare exact immutable delivery bytes once, outside the result/admission path. */
public interface ExecutionRecorder extends Function<ExecutionDecision, String> {

	Supplier<String> prepare(ExecutionDecision decision);

	/** Provider source preparation is optional and independent of the accepted result. */
	default Supplier<String> prepare(ExecutionDecision decision, java.util.Map<String, Object> evidence) {
		return prepare(decision);
	}

	@Override
	default String apply(ExecutionDecision decision) {
		return prepare(decision).get();
	}

}
