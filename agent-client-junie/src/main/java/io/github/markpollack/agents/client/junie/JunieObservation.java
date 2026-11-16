/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.markpollack.agents.model.AgentResponse;

/**
 * What one Junie turn established, as observed facts: the native session directories
 * before {@code session/new}, the ACP session id, the CLI version Junie reported, the
 * stop reason, and whether every process the turn started was seen to end.
 *
 * <p>
 * The processes are this JVM's children that appeared between the snapshot and
 * {@code session/new}, and their descendants. A concurrent launch from the same JVM in
 * that window is counted too, so the observation can only wait longer or report the
 * processes as not terminated; it never reports a live process as ended.
 */
final class JunieObservation {

	static final Duration EXIT_WAIT = Duration.ofSeconds(10);

	private final Path sessionsDirectory;

	final Instant start = Instant.now();

	private Set<String> sessionsBefore = Set.of();

	private Set<Long> childrenBefore = Set.of();

	private final List<ProcessHandle> processes = new ArrayList<>();

	private volatile String acpSessionId;

	private volatile String stopReason;

	private volatile String agentVersion;

	private volatile boolean terminated;

	private volatile boolean dispatched;

	JunieObservation(Path sessionsDirectory) {
		this.sessionsDirectory = sessionsDirectory;
	}

	/** Snapshot before the ACP process starts, and so before {@code session/new}. */
	void before() {
		this.sessionsBefore = sessions();
		this.childrenBefore = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
		this.dispatched = true;
	}

	/** Bind the {@code session/new} id and the processes started for it. */
	synchronized void sessionOpened(String sessionId, String version) {
		this.acpSessionId = sessionId;
		this.agentVersion = version;
		ProcessHandle.current()
			.children()
			.filter(child -> !this.childrenBefore.contains(child.pid()))
			.flatMap(child -> Stream.concat(Stream.of(child), child.descendants()))
			.forEach(this.processes::add);
	}

	void response(AgentResponse response) {
		Map<String, Object> fields = response.getMetadata() == null ? Map.of()
				: response.getMetadata().getProviderFields();
		if (fields == null)
			return;
		if (fields.get("stopReason") instanceof String reason)
			this.stopReason = reason;
	}

	/**
	 * After the turn returned: wait, bounded, for every observed process to end. No
	 * observed process means nothing was seen to end.
	 */
	void ended() {
		List<ProcessHandle> all;
		synchronized (this) {
			var seen = new ArrayList<>(this.processes);
			for (ProcessHandle process : this.processes)
				process.descendants().filter(d -> !seen.contains(d)).forEach(seen::add);
			all = List.copyOf(seen);
		}
		// An interrupted worker (a stop) still waits for the processes, then keeps its
		// interrupt.
		boolean interrupted = Thread.interrupted();
		long deadline = System.nanoTime() + EXIT_WAIT.toNanos();
		boolean ended = !all.isEmpty();
		for (ProcessHandle process : all) {
			try {
				process.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException ex) {
				interrupted = true;
			}
			catch (ExecutionException | TimeoutException ex) {
				ended = false;
			}
			ended &= !process.isAlive();
		}
		this.terminated = ended;
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	/** Sealing found the turn still running: nothing it started is known to have ended. */
	void notEnded() {
		this.terminated = false;
	}

	/** Native session directory names now present that were absent before. */
	List<String> newSessions() {
		var now = new TreeSet<>(sessions());
		now.removeAll(this.sessionsBefore);
		return List.copyOf(now);
	}

	boolean preexisting(String sessionId) {
		return this.sessionsBefore.contains(sessionId);
	}

	private Set<String> sessions() {
		if (!Files.isDirectory(this.sessionsDirectory))
			return Set.of();
		try (Stream<Path> entries = Files.list(this.sessionsDirectory)) {
			return entries.filter(Files::isDirectory)
				.map(p -> p.getFileName().toString())
				.collect(Collectors.toCollection(TreeSet::new));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	String acpSessionId() {
		return this.acpSessionId;
	}

	String stopReason() {
		return this.stopReason;
	}

	String agentVersion() {
		return this.agentVersion;
	}

	boolean terminated() {
		return this.terminated;
	}

	boolean dispatched() {
		return this.dispatched;
	}

	Path sessionsDirectory() {
		return this.sessionsDirectory;
	}

	/**
	 * The build number in Junie's reported version: {@code 26.10.20 (3813.7)} is
	 * {@code 3813.7}; null when Junie reported none.
	 */
	static String buildNumber(String reported) {
		if (reported == null || reported.isBlank())
			return null;
		int open = reported.lastIndexOf('(');
		int close = reported.lastIndexOf(')');
		if (open >= 0 && close > open + 1)
			return reported.substring(open + 1, close).trim();
		return reported.trim();
	}

}
