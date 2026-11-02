/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;

record ExecutionWork(AgentOptions options, Duration timeout, ApplicationCorrelation correlation,
		Function<AgentApi, AgentClientResponse> invoke, Consumer<AgentClientEvent> observer,
		Consumer<ExecutionDecision> recorder) {
}
