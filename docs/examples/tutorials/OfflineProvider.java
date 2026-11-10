/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.ProviderReference;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * The offline stand-in the tutorials run against in the build. It makes no model call. Each
 * conversation keeps a native history that outlives its "process", the way a CLI's session
 * file outlives the CLI. Like the retained Claude provider on main, a turn on a dead process
 * reconnects to that same history under a new connection id.
 *
 * <p>
 * A goal containing {@code [hold]} runs until {@link #release()}, so a tutorial can show
 * what happens while a turn is running. A stopped turn takes {@link #STOP_MILLIS} to exit,
 * like a process being torn down.
 */
public final class OfflineProvider implements AgentExecutionProvider {

	/** How long a stopped turn takes to exit. */
	public static final long STOP_MILLIS = 200;

	/** Native histories by native session id; they survive a dead process. */
	private final Map<String, List<String>> histories = new ConcurrentHashMap<>();

	/** Every dispatched goal, in dispatch order. */
	private final List<String> dispatched = new CopyOnWriteArrayList<>();

	private final List<Connection> connections = new CopyOnWriteArrayList<>();

	private volatile CountDownLatch held = new CountDownLatch(1);

	/** Goals in the order the provider received them. */
	public List<String> dispatched() {
		return List.copyOf(this.dispatched);
	}

	/** Lets every held turn finish. */
	public void release() {
		this.held.countDown();
	}

	/** Holds later {@code [hold]} turns again. */
	public void hold() {
		this.held = new CountDownLatch(1);
	}

	/** Simulates a provider process dying between turns. */
	public void killProcesses() {
		this.connections.forEach(connection -> connection.alive = false);
	}

	@Override
	public AgentExecutionProvider.Connection open(AgentOptions options) {
		return open(options, false);
	}

	@Override
	public AgentExecutionProvider.Connection open(AgentOptions options, boolean retained) {
		var connection = new Connection();
		this.connections.add(connection);
		return connection;
	}

	final class Connection implements AgentExecutionProvider.Connection {

		private final String nativeId = UUID.randomUUID().toString();

		private volatile String generation = UUID.randomUUID().toString();

		private volatile boolean alive = true;

		@Override
		public Optional<ProviderReference> providerReference() {
			return Optional.of(new ProviderReference("offline", "tutorial", this.nativeId));
		}

		@Override
		public Optional<String> connectionId() {
			return Optional.of(this.generation);
		}

		@Override
		public AgentExecutionProvider.Turn prepare(String executionId) {
			if (!this.alive) {
				// Reconnect to the same native history; nothing is replayed.
				this.generation = UUID.randomUUID().toString();
				this.alive = true;
			}
			return new Turn(this);
		}

		@Override
		public void close() {
			this.alive = false;
		}

	}

	final class Turn implements AgentExecutionProvider.Turn {

		private final Connection connection;

		private final CountDownLatch stopped = new CountDownLatch(1);

		Turn(Connection connection) {
			this.connection = connection;
		}

		@Override
		public AgentResponse call(AgentTaskRequest request) {
			String goal = request.goal();
			OfflineProvider.this.dispatched.add(goal);
			if (goal.contains("[hold]"))
				awaitReleaseOrStop();
			if (this.stopped.getCount() == 0) {
				pause(STOP_MILLIS);
				throw new CancellationException("stopped");
			}
			var history = OfflineProvider.this.histories.computeIfAbsent(this.connection.nativeId,
					id -> new CopyOnWriteArrayList<>());
			history.add(goal);
			String answer = "turn " + history.size() + " of " + this.connection.nativeId.substring(0, 8) + ": "
					+ goal;
			return new AgentResponse(
					List.of(new AgentGeneration(answer, new AgentGenerationMetadata("SUCCESS", Map.of()))));
		}

		@Override
		public void stop(boolean mayInterruptIfRunning) {
			// A stopped turn ends its process, as cancelling a CLI does.
			this.connection.alive = false;
			this.stopped.countDown();
		}

		/** Waits for release or stop; like a process, it ignores the waiting thread's interrupt. */
		private void awaitReleaseOrStop() {
			boolean interrupted = false;
			while (this.stopped.getCount() > 0) {
				try {
					if (OfflineProvider.this.held.await(10, TimeUnit.MILLISECONDS))
						break;
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
			}
			if (interrupted)
				Thread.currentThread().interrupt();
		}

		/** A process takes its time to exit even when the waiting thread is interrupted. */
		private void pause(long millis) {
			long end = System.nanoTime() + millis * 1_000_000;
			boolean interrupted = false;
			for (long left = end - System.nanoTime(); left > 0; left = end - System.nanoTime()) {
				try {
					Thread.sleep(Math.max(1, left / 1_000_000));
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
			}
			if (interrupted)
				Thread.currentThread().interrupt();
		}

	}

}
