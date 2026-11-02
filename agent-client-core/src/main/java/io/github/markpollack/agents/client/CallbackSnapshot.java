/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public record CallbackSnapshot(Optional<Instant> runningSince, int pendingCount, long droppedCount,
		long discardedOnCloseCount, long failureCount, Optional<String> lastFailure) {
}
