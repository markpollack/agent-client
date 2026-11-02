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

public record CleanupSnapshot(String operationId, CleanupState state, List<String> unconfirmedResources,
		Optional<Reason> reason) {
	public CleanupSnapshot {
		unconfirmedResources = List.copyOf(unconfirmedResources);
	}
}
