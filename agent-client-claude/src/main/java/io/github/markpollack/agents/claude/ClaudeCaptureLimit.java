/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

/**
 * How much of one Claude capture is held in memory. Captured bytes stay in memory because
 * they are observed on the transport's reader thread, which must not do file I/O; the
 * limit only keeps a runaway stream from exhausting the caller's heap. It is not a size
 * for ordinary runs: the largest real Claude stream observed so far is under 1 MiB.
 * Output beyond the limit is never silently lost: the capture keeps the prefix, names the
 * bytes it dropped and the limit, and cannot claim to be complete.
 *
 * @param bytes captured bytes, all streams together
 * @param observations byte-range observations a retained capture records
 */
record ClaudeCaptureLimit(int bytes, int observations) {

	/** 256 MiB of output and 131,072 observations per capture. */
	static final ClaudeCaptureLimit DEFAULT = new ClaudeCaptureLimit(256 * 1024 * 1024, 131_072);

	ClaudeCaptureLimit {
		if (bytes < 1 || observations < 1)
			throw new IllegalArgumentException("Capture limits must be positive");
		if (bytes > Integer.MAX_VALUE - 8)
			throw new IllegalArgumentException(
					"A capture is held in one array; at most " + (Integer.MAX_VALUE - 8) + " bytes");
	}

	/** The capture-error text for output dropped past this limit. */
	String dropped(long count) {
		return "Capture limit of " + this.bytes + " bytes reached; dropped " + count + " bytes";
	}

}
