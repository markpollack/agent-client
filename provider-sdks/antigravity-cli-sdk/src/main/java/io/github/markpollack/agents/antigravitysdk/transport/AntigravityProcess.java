/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravitysdk.transport;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import org.zeroturnaround.exec.*;

/**
 * One fresh direct process, raw pipes and bounded observed-resource cleanup; never
 * replays.
 */
public final class AntigravityProcess implements AutoCloseable {

	private final String executable;

	private final ExecuteOptions options;

	private final Map<String, String> environment;

	private final Map<Long, ProcessHandle> owned = new ConcurrentHashMap<>();

	private final ReentrantLock closing = new ReentrantLock();

	private volatile StartedProcess process;

	private volatile AntigravityPipes pipes;

	private volatile Thread watcher;

	private volatile boolean stopping;

	private boolean launched;

	public AntigravityProcess(String executable, ExecuteOptions options, Map<String, String> environment) {
		this.executable = Objects.requireNonNull(executable);
		this.options = Objects.requireNonNull(options);
		this.environment = Map.copyOf(environment);
	}

	/** Launch exactly once with the prompt in the print argument; stdin is empty. */
	public int run(byte[] prompt) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		try (var resource = this) {
			return resource.launch(prompt)
				.getFuture()
				.get(this.options.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.getExitValue();
		}
	}

	private synchronized StartedProcess launch(byte[] prompt) throws IOException {
		if (this.stopping)
			throw new CancellationException("Antigravity stopped before dispatch");
		if (this.launched)
			throw new IllegalStateException("Antigravity process cannot replay");
		this.launched = true;
		this.pipes = new AntigravityPipes(new byte[0]);
		this.process = new ProcessExecutor()
			.command(buildCommand(this.executable, new String(prompt, java.nio.charset.StandardCharsets.UTF_8),
					this.options))
			.directory(this.options.getWorkingDirectory().toFile())
			.environment(this.environment)
			.setMessageLogger(MessageLoggers.NOP)
			.redirectErrorStream(false)
			.streams(this.pipes)
			.exitValueAny()
			.destroyOnExit()
			.start();
		observe();
		this.watcher = Thread.ofVirtual().name("antigravity-owned-processes").start(this::watch);
		return this.process;
	}

	static List<String> buildCommand(String executable, String prompt, ExecuteOptions options) {
		return List.copyOf(CLITransport.buildCommand(executable, prompt, options, null));
	}

	private void watch() {
		while (!this.stopping) {
			observe();
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void observe() {
		var current = this.process;
		if (current == null)
			return;
		var root = current.getProcess().toHandle();
		this.owned.put(root.pid(), root);
		root.descendants().forEach(p -> this.owned.put(p.pid(), p));
	}

	public AntigravityBytes stdin() {
		return this.pipes.stdin;
	}

	public AntigravityBytes stdout() {
		return this.pipes.stdout;
	}

	public AntigravityBytes stderr() {
		return this.pipes.stderr;
	}

	public boolean dispatched() {
		return this.process != null;
	}

	public boolean terminated() {
		return this.stopping && this.owned.values().stream().noneMatch(ProcessHandle::isAlive)
				&& (this.pipes == null || this.pipes.stopped()) && (this.watcher == null || !this.watcher.isAlive());
	}

	public List<Long> observedPids() {
		return this.owned.keySet().stream().sorted().toList();
	}

	@Override
	public void close() {
		boolean interrupted = Thread.interrupted();
		this.closing.lock();
		try {
			stopOwned();
		}
		finally {
			this.closing.unlock();
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private void stopOwned() {
		synchronized (this) {
			this.stopping = true;
			observe();
		}
		var watch = this.watcher;
		if (watch != null) {
			watch.interrupt();
			try {
				watch.join(1000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		stopProcesses(false);
		awaitProcesses(200);
		stopProcesses(true);
		awaitProcesses(2000);
		if (this.pipes != null)
			this.pipes.finish();
		if (!terminated())
			throw new IllegalStateException("Antigravity observed-resource cessation is unconfirmed");
	}

	private void stopProcesses(boolean force) {
		this.owned.values().stream().filter(ProcessHandle::isAlive).forEach(p -> {
			if (force)
				p.destroyForcibly();
			else
				p.destroy();
		});
	}

	private void awaitProcesses(long millis) {
		long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (this.owned.values().stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < end) {
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

}
