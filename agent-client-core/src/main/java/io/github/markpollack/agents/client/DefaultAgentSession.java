/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import io.github.markpollack.agents.model.AgentOptions;

final class DefaultAgentSession implements AgentSession {

	private final String id = UUID.randomUUID().toString();

	final ReentrantLock lock = new ReentrantLock();

	private final java.util.concurrent.locks.Condition changed = this.lock.newCondition();

	private CleanupBudget closeBudget;

	private int optionalLanes;

	final AgentExecutionProvider provider;

	final DefaultAgentClient client;

	final AgentOptions options;

	volatile AgentExecutionProvider.Connection connection;

	volatile String connectionId;

	private boolean acquiring;

	private final boolean retained;

	boolean retained() {
		return this.retained;
	}

	private final java.util.concurrent.atomic.AtomicBoolean connectionClose = new java.util.concurrent.atomic.AtomicBoolean();

	volatile boolean closeFailed;

	private volatile boolean connectionClosed;

	void finishEphemeral() {
		if (!this.retained)
			closeConnection();
	}

	private void closeConnection() {
		if (this.connection != null && this.connectionClose.compareAndSet(false, true)) {
			try {
				this.connection.close();
				this.connectionClosed = true;
			}
			catch (RuntimeException ex) {
				this.closeFailed = true;
				throw ex;
			}
		}
	}

	void beginAcquisition() {
		this.lock.lock();
		try {
			if (this.closing)
				throw new IllegalStateException("Session is closing");
			this.acquiring = true;
		}
		finally {
			this.lock.unlock();
		}
	}

	void install(AgentExecutionProvider.Connection connection) {
		this.connection = java.util.Objects.requireNonNull(connection, "connection");
		this.connectionId = UUID.randomUUID().toString();
	}

	void endAcquisition() {
		this.lock.lock();
		try {
			this.acquiring = false;
			this.changed.signalAll();
		}
		finally {
			this.lock.unlock();
		}
		afterTermination();
	}

	private volatile SessionSnapshot snapshot;

	private ExecutionStore active;

	private boolean closing;

	private boolean closeStarted;

	DefaultAgentSession(DefaultAgentClient client, AgentExecutionProvider provider, AgentOptions options,
			boolean retained) {
		this.retained = retained;
		this.client = client;
		this.provider = provider;
		this.options = options;
		this.snapshot = new SessionSnapshot(this.id, SessionState.READY, Optional.empty(), Optional.empty(),
				Optional.empty());
	}

	DefaultAgentExecution submit(ExecutionWork work) {
		DefaultAgentExecution execution;
		this.lock.lock();
		try {
			if (this.closing || this.snapshot.state() != SessionState.READY)
				throw new IllegalStateException("Session is not ready: " + this.snapshot.state());
			execution = new DefaultAgentExecution(this, work, this.client.runtime);
			this.active = execution.store;
			this.optionalLanes++; // Hold ownership until observation lanes are
									// registered.
			this.snapshot = new SessionSnapshot(this.id, SessionState.BUSY, Optional.empty(),
					Optional.of(execution.executionId()), Optional.empty());
		}
		finally {
			this.lock.unlock();
		}
		execution.driver.submit();
		return execution;
	}

	// Called under the admission lock. UNKNOWN is permanently quarantined.
	void release(ExecutionStore store, boolean unknown) {
		if (this.active != store)
			return;
		if (this.snapshot.state() == SessionState.UNKNOWN) {
			if (!unknown)
				this.active = null;
			return;
		}
		SessionState state = unknown ? SessionState.UNKNOWN : this.closing ? SessionState.CLOSING : SessionState.READY;
		this.snapshot = new SessionSnapshot(this.id, state, Optional.empty(),
				unknown ? Optional.of(store.execution.executionId()) : Optional.empty(), Optional.empty());
		if (!unknown)
			this.active = null;
	}

	@Override
	public String id() {
		return this.id;
	}

	@Override
	public Optional<ProviderReference> providerReference() {
		return Optional.empty();
	}

	@Override
	public SessionCapabilities capabilities() {
		return new SessionCapabilities(new Capability(true, Optional.empty()),
				new Capability(false, Optional.of("Load is not implemented")),
				new Capability(false, Optional.of("Native progress is not implemented")));
	}

	@Override
	public AgentClient.AgentClientRequestSpec goal(String prompt) {
		return this.client.sessionGoal(this, prompt);
	}

	@Override
	public SessionSnapshot inspect() {
		return this.snapshot;
	}

	@Override
	public void close() {
		ExecutionContext.checkClose(this.client, this);
		requestClose(new CleanupBudget(this.client.runtime.cleanupNanos));
		awaitClose();
	}

	void requestClose(CleanupBudget budget) {
		ExecutionStore current;
		this.lock.lock();
		try {
			if (this.closeBudget == null || budget.remaining() < this.closeBudget.remaining())
				this.closeBudget = budget;
			this.closing = true;
			current = this.active;
			if (current != null && current.cleanupStarted) {
				long left = this.client.runtime.cleanupNanos
						- (this.client.runtime.clock.getAsLong() - current.cleanupStart);
				if (left < this.closeBudget.remaining())
					this.closeBudget = new CleanupBudget(left);
			}
			if (this.snapshot.state() == SessionState.READY)
				this.snapshot = new SessionSnapshot(this.id, SessionState.CLOSING, providerReference(),
						Optional.empty(), Optional.empty());
		}
		finally {
			this.lock.unlock();
		}
		if (current != null)
			current.execution.cancel(true);
		afterTermination();
	}

	void awaitClose() {
		boolean interrupted = Thread.interrupted();
		this.lock.lock();
		try {
			while (!resourcesSettled() && this.closeBudget.remaining() > 0) {
				try {
					this.changed.awaitNanos(this.closeBudget.remaining());
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
			}
			if (!resourcesSettled()) {
				closeExpired();
				throw new CleanupUnconfirmedException("Session cleanup unconfirmed", cleanup());
			}
			this.client.forget(this);
		}
		finally {
			this.lock.unlock();
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private boolean resourcesSettled() {
		return !this.acquiring && this.active == null && this.optionalLanes == 0
				&& (this.connection == null || this.connectionClosed) && !this.closeFailed;
	}

	CleanupHandle cleanup() {
		return new CleanupHandle() {
			@Override
			public String id() {
				return DefaultAgentSession.this.id;
			}

			@Override
			public CleanupSnapshot inspect() {
				lock.lock();
				try {
					var resources = new ArrayList<String>();
					if (acquiring)
						resources.add("session-acquisition");
					if (active != null)
						resources.add("execution-worker-or-stop");
					if (connection != null && !connectionClosed)
						resources.add("provider-connection");
					if (optionalLanes != 0)
						resources.add("observer-or-recorder");
					return new CleanupSnapshot(id(),
							resources.isEmpty() ? CleanupState.CONFIRMED : CleanupState.UNKNOWN, resources,
							Optional.empty());
				}
				finally {
					lock.unlock();
				}
			}
		};
	}

	boolean isClosing() {
		this.lock.lock();
		try {
			return this.closing;
		}
		finally {
			this.lock.unlock();
		}
	}

	void beginOptional() {
		this.lock.lock();
		try {
			this.optionalLanes++;
		}
		finally {
			this.lock.unlock();
		}
	}

	void endOptional() {
		this.lock.lock();
		try {
			this.optionalLanes--;
			this.changed.signalAll();
		}
		finally {
			this.lock.unlock();
		}
		afterTermination();
	}

	private void closeExpired() {
		this.lock.lock();
		try {
			if (this.snapshot.state() != SessionState.CLOSED)
				this.snapshot = new SessionSnapshot(this.id, SessionState.UNKNOWN, Optional.empty(),
						this.snapshot.activeExecutionId(), Optional.empty());
		}
		finally {
			this.lock.unlock();
		}
	}

	void afterTermination() {
		this.lock.lock();
		try {
			this.changed.signalAll();
			if ((!this.retained || this.closing) && resourcesSettled())
				this.client.forget(this);
			if (!this.closing || this.active != null || this.closeStarted || this.acquiring)
				return;
			this.closeStarted = true;
		}
		finally {
			this.lock.unlock();
		}
		try {
			this.client.runtime.lanes.stop().execute(() -> ExecutionContext.run(this, this::finishClose));
		}
		catch (java.util.concurrent.RejectedExecutionException ex) {
			closeExpired();
		}
	}

	private void finishClose() {
		try {
			closeConnection();
		}
		catch (RuntimeException ex) {
			closeExpired();
			return;
		}
		this.lock.lock();
		try {
			if (this.snapshot.state() != SessionState.UNKNOWN)
				this.snapshot = new SessionSnapshot(this.id, SessionState.CLOSED, Optional.empty(), Optional.empty(),
						Optional.empty());
		}
		finally {
			this.lock.unlock();
		}
		afterTermination();
	}

}
