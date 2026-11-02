/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.Objects;

final class ExecutionTimes {

	private ExecutionTimes() {
	}

	static long nanos(Duration duration, boolean positive) {
		Objects.requireNonNull(duration, "duration");
		if (duration.isNegative() || (positive && duration.isZero())) {
			throw new IllegalArgumentException("Duration must be " + (positive ? "positive" : "nonnegative"));
		}
		try {
			return duration.toNanos();
		}
		catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	static boolean elapsed(long now, long start, long allowance) {
		return now - start >= allowance;
	}

}
