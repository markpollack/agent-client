/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.concurrent.TimeUnit;

/** One real monotonic budget shared by all close participants and repeated callers. */
final class CleanupBudget {

	private final long start = System.nanoTime();

	private final long nanos;

	CleanupBudget(long nanos) {
		this.nanos = Math.max(0, nanos);
	}

	long remaining() {
		return Math.max(0, this.nanos - (System.nanoTime() - this.start));
	}

}
