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

public interface AgentExecution {

	String executionId();

	String sessionId();

	boolean cancel(boolean mayInterruptIfRunning);

	boolean cancel(boolean mayInterruptIfRunning, Reason reason);

	boolean isCancelled();

	boolean isDone();

	/**
	 * Ready-first, otherwise interruptible waiter-only observation. Fresh outer
	 * exceptions.
	 */
	AgentClientResponse result() throws InterruptedException;

	AgentClientResponse result(Duration waitTimeout) throws InterruptedException, TimeoutException;

	CompletionStage<ExecutionTermination> termination();

	ExecutionTermination awaitTermination(Duration timeout) throws InterruptedException, TimeoutException;

	Optional<ExecutionDecision> decision();

	RecordingSnapshot recording();

	ExecutionSnapshot inspect();

	Optional<AgentClientResponse> responseSnapshot();

}
