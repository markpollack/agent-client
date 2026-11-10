/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentOperationException;
import io.github.markpollack.agents.client.AgentSession;
import io.github.markpollack.agents.client.ExecutionTermination;

/**
 * Tutorial 2: a shared conversation, in arrival order. Several users' inputs go to one
 * conversation through this bounded first-in-first-out inbox, with one worker per
 * conversation. The inbox is application code, not a library API: Agent Client runs one turn
 * at a time per session and refuses an overlapping submit as {@code BUSY}; the application
 * decides what waits, in what order, and how inputs are attributed.
 */
public final class ConversationInbox implements AutoCloseable {

	/** One user's input. */
	public record Input(String user, String text) {
	}

	/** What the HTTP layer tells the caller. */
	public enum Admission {

		/** Accepted; it runs after the inputs ahead of it (HTTP 202). */
		QUEUED,
		/** The inbox is full; try again later (HTTP 429). */
		FULL

	}

	private final AgentSession conversation;

	private final BlockingQueue<Input> inbox;

	private final BiConsumer<Input, AgentExecution> started;

	private final Thread worker;

	private volatile AgentExecution current;

	/**
	 * @param conversation the shared conversation
	 * @param capacity how many inputs may wait
	 * @param started called as each input starts, for example to stream its result back
	 */
	public ConversationInbox(AgentSession conversation, int capacity, BiConsumer<Input, AgentExecution> started) {
		this.conversation = conversation;
		this.inbox = new ArrayBlockingQueue<>(capacity);
		this.started = started;
		this.worker = Thread.ofVirtual().name("inbox-" + conversation.id()).start(this::drain);
	}

	/** An HTTP request for this conversation: queue it behind the inputs already waiting. */
	public Admission submit(Input input) {
		return this.inbox.offer(input) ? Admission.QUEUED : Admission.FULL;
	}

	/** The turn running now, if any. */
	public AgentExecution current() {
		return this.current;
	}

	/** Each input names its user, so the shared history says who asked what. */
	static String attributed(Input input) {
		return "From " + input.user() + ": " + input.text();
	}

	private void drain() {
		try {
			while (true) {
				Input next = this.inbox.take();
				AgentExecution execution = this.conversation.goal(attributed(next)).runAsync();
				this.current = execution;
				this.started.accept(next, execution);
				// Advance on termination, not on the result: a cancelled turn's result is
				// done at once, but its process may still be stopping and the session
				// would refuse the next input as BUSY.
				ExecutionTermination ended = execution.termination().toCompletableFuture().get();
				if (!ended.cleanupConfirmed())
					return; // the session is quarantined; nothing more can run in it
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException ex) {
			throw new IllegalStateException(ex.getCause());
		}
	}

	/** Stops taking inputs. The conversation itself is closed by its owner. */
	@Override
	public void close() throws InterruptedException {
		this.worker.interrupt();
		this.worker.join();
	}

	/**
	 * Without an inbox, an HTTP endpoint submits directly and maps the refusal. Agent Client
	 * never queues a second prompt behind a running one.
	 * @return the HTTP status for this attempt
	 */
	public static int submitDirectly(AgentSession conversation, Input input) {
		try {
			conversation.goal(attributed(input)).runAsync();
			return 202;
		}
		catch (AgentOperationException refused) {
			return switch (refused.failure()) {
				case BUSY -> 409; // a turn is running: Conflict, or queue it as above
				case CLEANUP_UNKNOWN -> 503; // quarantined: start a new conversation
				case CLOSED -> 410; // the conversation has ended
				default -> 500;
			};
		}
	}

}
