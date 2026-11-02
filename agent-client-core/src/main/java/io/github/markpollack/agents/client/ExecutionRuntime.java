/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Separate lanes keep arbitrary callbacks off control, provider and clock threads. */
final class ExecutionRuntime {

	interface Timer {

		Runnable schedule(Runnable task, long nanos);

	}

	record Lanes(Executor worker, Executor stop, Executor publication, Executor observer, Executor recorder) {
	}

	private static final ScheduledExecutorService CLOCK = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("agent-clock").factory());

	final LongSupplier clock;

	final Supplier<Instant> wall;

	final Timer timer;

	final Lanes lanes;

	Executor acquisition = virtualLane("agent-setup-");

	final long setupNanos;

	final long cleanupNanos;

	ExecutionRuntime(Duration setup, Duration cleanup) {
		this(System::nanoTime, Instant::now, (task, nanos) -> {
			var scheduled = CLOCK.schedule(task, nanos, TimeUnit.NANOSECONDS);
			return () -> scheduled.cancel(false);
		}, new Lanes(virtualLane("agent-work-"), virtualLane("agent-stop-"), virtualLane("agent-publish-"),
				boundedLane("agent-observer-", 32), boundedLane("agent-recorder-", 256)), new Limits(setup, cleanup));
	}

	record Limits(Duration setup, Duration cleanup) {
	}

	ExecutionRuntime(LongSupplier clock, Supplier<Instant> wall, Timer timer, Lanes lanes, Limits limits) {
		this.clock = clock;
		this.wall = wall;
		this.timer = timer;
		this.lanes = lanes;
		this.setupNanos = ExecutionTimes.nanos(limits.setup(), true);
		this.cleanupNanos = ExecutionTimes.nanos(limits.cleanup(), true);
	}

	private static Executor virtualLane(String name) {
		return task -> Thread.ofVirtual().name(name).start(task);
	}

	private static Executor boundedLane(String name, int capacity) {
		var executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
				Thread.ofPlatform().daemon().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

}
