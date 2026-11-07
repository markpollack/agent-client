/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.time.Instant;
import java.util.*;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver;

/**
 * Bounded memory acquisition ({@link ClaudeCaptureLimit}). All filesystem/Journal work
 * occurs on the recording lane.
 */
final class ClaudeClientSources {

	private final Instant started = Instant.now();

	private final List<Chunk> chunks = new ArrayList<>();

	private final ClaudeCaptureLimit limit;

	private long bytes;

	private long dropped;

	private boolean sealed;

	static final class Chunk {

		final TransportByteObserver.Stream stream;

		final byte[] data;

		final Instant time;

		Chunk(TransportByteObserver.Stream stream, byte[] data, Instant time) {
			this.stream = stream;
			this.data = data;
			this.time = time;
		}

	}

	private Map<String, Object> process = Map.of();

	ClaudeClientSources() {
		this(ClaudeCaptureLimit.DEFAULT);
	}

	ClaudeClientSources(ClaudeCaptureLimit limit) {
		this.limit = Objects.requireNonNull(limit);
	}

	void observeProcess(io.github.markpollack.claude.agent.sdk.ClaudeSyncClient client) {
		if (!this.process.isEmpty())
			return;
		rootProcess(client).ifPresent(p -> this.process = Map.of("pid", p.pid(), "argv", argv(p), "basis",
				"SDK_owned_root_handle", "observed_at", Instant.now().toString()));
	}

	/** The SDK's own root process handle, when it is still enumerable. */
	static Optional<ProcessHandle> rootProcess(io.github.markpollack.claude.agent.sdk.ClaudeSyncClient client) {
		return client.transportResources()
			.flatMap(snapshot -> snapshot.processes().stream().filter(p -> p.root()).findFirst())
			.flatMap(p -> ProcessHandle.of(p.pid()));
	}

	static List<String> argv(ProcessHandle process) {
		var argv = new ArrayList<String>();
		process.info().command().ifPresent(argv::add);
		process.info().arguments().ifPresent(args -> argv.addAll(List.of(args)));
		return List.copyOf(argv);
	}

	void observe(TransportByteObserver.Stream stream, byte[] data) {
		Instant observedAt = Instant.now();
		synchronized (this) {
			if (this.sealed || data.length > this.limit.bytes() - this.bytes) {
				this.dropped += data.length;
				return;
			}
			this.chunks.add(new Chunk(stream, data.clone(), observedAt));
			this.bytes += data.length;
		}
	}

	/** The capture error for output dropped past the limit so far; null when none was. */
	synchronized String droppedFailure() {
		return this.dropped == 0 ? null : this.limit.dropped(this.dropped);
	}

	ClaudeAcquisition seal(ClaudeClientGeneration generation, ClaudeExecutionResult result, String executionId,
			String prompt) {
		List<Chunk> copied;
		long missing;
		synchronized (this) {
			if (this.sealed)
				throw new IllegalStateException("Source acquisition already prepared");
			this.sealed = true;
			copied = List.copyOf(this.chunks);
			this.chunks.clear();
			missing = this.dropped;
		}
		var options = new ClaudeCaptureOptions(generation.config.captureDirectory().resolve(executionId), executionId,
				generation.config.cliVersion());
		var capture = new ClaudeCaptureSession(options, generation.id, this.started);
		capture.observeProcess(this.process);
		String failure = missing == 0 ? null : this.limit.dropped(missing);
		var resources = generation.client.transportResources().orElseThrow();
		if (!resources.stdout().eof() || !resources.stderr().eof())
			failure = "Natural source EOF not observed";
		try {
			for (var chunk : copied)
				capture.wire().onBytes(chunk.stream, chunk.data, chunk.time);
		}
		catch (java.io.IOException ex) {
			failure = ex.toString();
		}
		return capture.seal(result, failure, generation.cli, generation.directory, prompt, true);
	}

}
