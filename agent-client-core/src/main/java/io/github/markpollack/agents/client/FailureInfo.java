/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.List;
import java.util.Objects;

/**
 * Frozen diagnostics; causes summarize underlying causes, excluding the primary
 * exception. Never regenerated from the separately retained mutable Throwable.
 */
public record FailureInfo(FailureCode code, String exceptionClass, String message, List<String> causes) {
	public FailureInfo {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(exceptionClass, "exceptionClass");
		Objects.requireNonNull(message, "message");
		causes = List.copyOf(causes);
	}
}
