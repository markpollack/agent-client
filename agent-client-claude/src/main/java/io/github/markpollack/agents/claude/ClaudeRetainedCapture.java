/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver;

/**
 * Bounded original bytes for one retained connection generation. A recorder receives the
 * same handle under {@code claudeRetainedCapture} for each execution. Record its
 * immutable decision immediately; after session close, explicitly export this diagnostic
 * capture. This is not a Journal acquisition or a qualified per-execution native archive.
 */
public final class ClaudeRetainedCapture {

	private static final int LIMIT = 4 * 1024 * 1024;

	private final String connectionId;

	private final Path directory;

	private final String cliVersion;

	private final EnumMap<TransportByteObserver.Stream, ByteArrayOutputStream> streams = new EnumMap<>(
			TransportByteObserver.Stream.class);

	private final EnumMap<TransportByteObserver.Stream, Long> observed = new EnumMap<>(
			TransportByteObserver.Stream.class);

	private final List<Map<String, Object>> observations = new ArrayList<>();

	private final List<Map<String, Object>> turns = new ArrayList<>();

	private int captured;

	private boolean overflow;

	private boolean sealed;

	private boolean stdoutEof;

	private boolean stderrEof;

	private boolean terminated;

	private String nativeSession;

	private Path manifest;

	ClaudeRetainedCapture(String connectionId, Path directory, String cliVersion) {
		this.connectionId = connectionId;
		this.directory = directory.resolve(connectionId);
		this.cliVersion = cliVersion;
		for (var stream : TransportByteObserver.Stream.values()) {
			this.streams.put(stream, new ByteArrayOutputStream());
			this.observed.put(stream, 0L);
		}
	}

	/** Exact owned generation; logical session and D remain in the caller's decision. */
	public String connectionId() {
		return this.connectionId;
	}

	/**
	 * True only after the SDK close attempt; says nothing about natural EOF or integrity.
	 */
	public synchronized boolean sealed() {
		return this.sealed;
	}

	void observe(TransportByteObserver.Stream stream, byte[] bytes) {
		Instant time = Instant.now();
		synchronized (this) {
			if (this.sealed)
				return;
			long start = this.observed.get(stream);
			this.observed.put(stream, start + bytes.length);
			if (this.overflow || bytes.length > LIMIT - this.captured || this.observations.size() >= 16384) {
				this.overflow = true;
				return;
			}
			this.streams.get(stream).writeBytes(bytes);
			this.captured += bytes.length;
			this.observations.add(Map.of("stream", stream.name(), "start", start, "end", start + bytes.length,
					"observedAt", time.toString()));
		}
	}

	synchronized void begin(String executionId) {
		if (this.turns.size() >= 256) {
			this.overflow = true;
			return;
		}
		var turn = new LinkedHashMap<String, Object>();
		turn.put("executionId", executionId);
		turn.put("start", offsets());
		turn.put("dispatchObservedAt", Instant.now().toString());
		this.turns.add(turn);
	}

	synchronized void end(String executionId, String session) {
		this.nativeSession = session;
		for (var turn : this.turns) {
			if (executionId.equals(turn.get("executionId"))) {
				turn.put("end", offsets());
				turn.put("terminalObservedAt", Instant.now().toString());
				turn.put("nativeSessionId", session == null ? "unknown" : session);
			}
		}
	}

	synchronized void seal(boolean terminated, boolean stdoutEof, boolean stderrEof) {
		if (this.sealed)
			return;
		this.terminated = terminated;
		this.stdoutEof = stdoutEof;
		this.stderrEof = stderrEof;
		this.sealed = true;
	}

	private Map<String, Long> offsets() {
		var result = new LinkedHashMap<String, Long>();
		this.observed.forEach((stream, count) -> result.put(stream.name(), count));
		return Map.copyOf(result);
	}

	/** Export after close, using the ordinary Claude projects directory. */
	public Path write() throws IOException {
		return write(Path.of(System.getProperty("user.home"), ".claude", "projects"));
	}

	/**
	 * Export once to the configured new generation directory. Files are never
	 * overwritten. Native files are a final stable snapshot only; per-execution
	 * membership and boundary prefix observations await a qualified retained archive
	 * contract. Invoke off the execution/result path. A failed write leaves diagnostic
	 * files and is not retried.
	 * @param nativeProjectsDirectory exact native projects root, also usable by fixtures
	 * @return Client diagnostic manifest, never a Journal acquisition manifest
	 */
	public synchronized Path write(Path nativeProjectsDirectory) throws IOException {
		if (!this.sealed)
			throw new IllegalStateException("Generation is still open; a turn result is not EOF");
		if (this.manifest != null)
			return this.manifest;
		Files.createDirectories(this.directory.getParent());
		Files.createDirectory(this.directory);
		if (Files.getFileStore(this.directory).supportsFileAttributeView("posix"))
			Files.setPosixFilePermissions(this.directory,
					java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		var raw = new EnumMap<TransportByteObserver.Stream, byte[]>(TransportByteObserver.Stream.class);
		var sources = writeStreams(raw);
		var values = describe(raw);
		values.put("sources", sources);
		addNativeSnapshot(values, nativeProjectsDirectory);
		Path output = this.directory.resolve("retained-capture.json");
		Files.writeString(output, CaptureFiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values),
				StandardOpenOption.CREATE_NEW);
		this.manifest = output;
		return output;
	}

	private List<Map<String, Object>> writeStreams(Map<TransportByteObserver.Stream, byte[]> raw) throws IOException {
		var sources = new ArrayList<Map<String, Object>>();
		for (var entry : this.streams.entrySet()) {
			byte[] bytes = entry.getValue().toByteArray();
			raw.put(entry.getKey(), bytes);
			Path file = this.directory.resolve(entry.getKey().name().toLowerCase(Locale.ROOT) + ".bin");
			Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
			sources.add(Map.of("stream", entry.getKey().name(), "path", file.getFileName().toString(), "sha256",
					CaptureFiles.hash(file), "captured", bytes.length, "observed", this.observed.get(entry.getKey()),
					"dropped", this.observed.get(entry.getKey()) - bytes.length));
		}
		return sources;
	}

	private Map<String, Object> describe(Map<TransportByteObserver.Stream, byte[]> raw) {

		var values = new LinkedHashMap<String, Object>();
		values.put("kind", "client-retained-capture/1");
		values.put("connectionId", this.connectionId);
		values.put("nativeSessionId", this.nativeSession);
		values.put("cliVersion", this.cliVersion);
		values.put("sealed", this.sealed);
		values.put("observedResourcesTerminated", this.terminated);
		values.put("stdoutNaturalEof", this.stdoutEof);
		values.put("stderrNaturalEof", this.stderrEof);
		values.put("overflow", this.overflow);
		values.put("transportComplete", this.terminated && this.stdoutEof && this.stderrEof && !this.overflow);
		values.put("archiveQualified", false);
		values.put("observations", this.observations);
		values.put("turns", RetainedCaptureLocators.describe(this.turns, raw));
		values.put("membership", "Observation windows only; controls/read-ahead/unmatched records remain unassigned");
		values.put("nativeAttribution", "Final snapshot only; per-turn membership and prefix high-water not qualified");
		return values;
	}

	private void addNativeSnapshot(Map<String, Object> values, Path nativeProjectsDirectory) {
		try {
			var options = new ClaudeCaptureOptions(this.directory, this.connectionId, this.cliVersion,
					nativeProjectsDirectory, Map.of());
			var nativeFiles = new ArrayList<Map<String, Object>>();
			for (var source : ClaudeNativeArchive.acquire(options, this.nativeSession)) {
				var file = new LinkedHashMap<>(source);
				nativeFiles.add(file);
				file.put("end", "snapshot_extent");
				file.put("membership", "generation_snapshot_not_execution_attribution");
			}
			values.put("nativeFiles", nativeFiles);
		}
		catch (IOException ex) {
			values.put("nativeCaptureError", ex.toString());
		}
	}

}
