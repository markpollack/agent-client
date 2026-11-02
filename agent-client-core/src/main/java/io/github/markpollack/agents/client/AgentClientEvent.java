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

public record AgentClientEvent(String executionId, String sessionId, long sequence, Instant time, EventKind kind,
		Map<String, Object> data) {
	public AgentClientEvent {
		data = Map.copyOf(data);
	}
}
