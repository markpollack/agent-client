/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codexsdk.transport;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import org.zeroturnaround.exec.*;

/**
 * One fresh direct process, raw pipes and bounded observed-resource cleanup; never
 * replays.
 */
public final class CodexProcess implements AutoCloseable {

	private final String executable;

	private final ExecuteOptions options;

	private final String resumeThreadId;

	private final boolean untimed;

	private final Map<Long, ProcessHandle> owned = new ConcurrentHashMap<>();

	private final ReentrantLock closing = new ReentrantLock();

	private volatile StartedProcess process;

	private volatile CodexPipes pipes;

	private volatile Thread watcher;

	private volatile boolean stopping;

	private boolean launched;

	public CodexProcess(String executable, ExecuteOptions options) {
		this(executable, options, null);
	}

	/**
	 * Resume the exact native thread previously emitted by Codex; null starts a fresh
	 * thread. The prompt still arrives on stdin and no earlier prompt is replayed.
	 */
	public CodexProcess(String executable, ExecuteOptions options, String resumeThreadId) {
		this(executable, options, resumeThreadId, false);
	}

	/**
	 * Optionally omit the elapsed process-wait deadline. Existing constructors remain
	 * timed; cancellation, resource ownership and bounded cleanup are unchanged.
	 */
	public CodexProcess(String executable, ExecuteOptions options, String resumeThreadId, boolean untimed) {
		this.executable = Objects.requireNonNull(executable);
		this.options = Objects.requireNonNull(options);
		if (resumeThreadId != null && !resumeThreadId.matches("[A-Za-z0-9-]+"))
			throw new IllegalArgumentException("Unsafe Codex thread identity");
		this.resumeThreadId = resumeThreadId;
		this.untimed = untimed;
	}

	/** Launch exactly once, with the prompt written to stdin rather than argv. */
	public int run(byte[] prompt) throws IOException, InterruptedException, ExecutionException, TimeoutException {
		try (var resource = this) {
			var completion = resource.launch(prompt).getFuture();
			return (this.untimed ? completion.get()
					: completion.get(this.options.getTimeout().toMillis(), TimeUnit.MILLISECONDS))
				.getExitValue();
		}
	}

	private synchronized StartedProcess launch(byte[] prompt) throws IOException {
		if (this.stopping)
			throw new CancellationException("Codex stopped before dispatch");
		if (this.launched)
			throw new IllegalStateException("Codex process cannot replay");
		this.launched = true;
		this.pipes = new CodexPipes(prompt);
		this.process = new ProcessExecutor().command(argv())
			.directory(this.options.getWorkingDirectory().toFile())
			.environment(this.options.getEnvironment())
			.setMessageLogger(MessageLoggers.NOP)
			.redirectErrorStream(false)
			.streams(this.pipes)
			.exitValueAny()
			.destroyOnExit()
			.start();
		observe();
		this.watcher = Thread.ofVirtual().name("codex-owned-processes").start(this::watch);
		return this.process;
	}

	/**
	 * Exact flags for the additive fresh-process route; legacy command construction is
	 * unchanged.
	 */
	public List<String> argv() {
		return buildCommand(this.executable, this.options, this.resumeThreadId);
	}

	public Optional<String> resumeThreadId() {
		return Optional.ofNullable(this.resumeThreadId);
	}

	static List<String> buildCommand(String executable, ExecuteOptions options) {
		return buildCommand(executable, options, null);
	}

	/**
	 * Fresh: {@code exec --ignore-user-config ... -- -}. Resume: {@code exec resume
	 * --ignore-user-config ... <thread> -- -} on the pinned CLI's documented resume
	 * route.
	 */
	static List<String> buildCommand(String executable, ExecuteOptions options, String resumeThreadId) {
		var args = new ArrayList<>(CodexCommandBuilder.build(executable, "-", options, resumeThreadId));
		args.add(1, "--no-daemon");
		int subcommand = resumeThreadId == null ? args.indexOf("exec") : args.indexOf("resume");
		args.add(subcommand + 1, "--ignore-user-config");
		return List.copyOf(args);
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

	public CodexBytes stdin() {
		return this.pipes.stdin;
	}

	public CodexBytes stdout() {
		return this.pipes.stdout;
	}

	public CodexBytes stderr() {
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
			throw new IllegalStateException("Codex observed-resource cessation is unconfirmed");
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
