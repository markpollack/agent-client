/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver;

/** Synchronous original-byte retention; index ordering is observation, not causality. */
final class ClaudeWireCapture implements AutoCloseable {

	private final Path directory;

	private final Map<TransportByteObserver.Stream, OutputStream> outputs = new EnumMap<>(
			TransportByteObserver.Stream.class);

	private final Map<TransportByteObserver.Stream, Long> offsets = new EnumMap<>(TransportByteObserver.Stream.class);

	private final List<String> failures = new ArrayList<>();

	private final OutputStream index;

	private boolean closed;

	ClaudeWireCapture(Path directory) throws IOException {
		this.directory = directory;
		index = Files.newOutputStream(directory.resolve("observations.jsonl"), StandardOpenOption.CREATE_NEW);
		try {
			for (var stream : TransportByteObserver.Stream.values()) {
				outputs.put(stream,
						Files.newOutputStream(directory.resolve(filename(stream)), StandardOpenOption.CREATE_NEW));
				offsets.put(stream, 0L);
			}
		}
		catch (IOException ex) {
			close();
			throw ex;
		}
	}

	synchronized void onBytes(TransportByteObserver.Stream stream, byte[] bytes) throws IOException {
		try {
			if (closed) {
				throw new IOException("Capture already closed");
			}
			long offset = offsets.get(stream);
			outputs.get(stream).write(bytes);
			offsets.put(stream, offset + bytes.length);
			var observation = Map.of("stream", stream.name(), "offset", offset, "bytes", bytes.length, "observed_at",
					Instant.now().toString(), "observation", stream == TransportByteObserver.Stream.STDIN
							? "successful_write_not_consumption" : "read_before_decoding");
			index.write((CaptureFiles.JSON.writeValueAsString(observation) + "\n").getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			failures.add(ex.toString());
			throw ex;
		}
	}

	synchronized List<String> failures() {
		return List.copyOf(failures);
	}

	synchronized Map<String, Long> offsets() {
		Map<String, Long> copy = new java.util.LinkedHashMap<>();
		offsets.forEach((stream, offset) -> copy.put(stream.name(), offset));
		return Map.copyOf(copy);
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		List<OutputStream> all = new ArrayList<>(outputs.values());
		all.add(index);
		for (OutputStream output : all) {
			try {
				output.close();
			}
			catch (IOException ex) {
				failures.add(ex.toString());
			}
		}
	}

	List<Map<String, Object>> sources(String connection, String invocation, boolean complete) throws IOException {
		List<Map<String, Object>> sources = new ArrayList<>();
		for (var stream : TransportByteObserver.Stream.values()) {
			String name = filename(stream);
			var source = CaptureFiles
				.source(directory, directory.resolve(name),
						stream == TransportByteObserver.Stream.STDOUT ? "stdout.jsonl" : name,
						stream == TransportByteObserver.Stream.STDOUT ? "stream"
								: stream.name().toLowerCase(java.util.Locale.ROOT),
						connection + "/" + name, invocation);
			source.put("required", true);
			source.put("end_basis", "Observer drained through SDK close; not an independent process exit receipt");
			source.put("end", complete ? "EOF" : "INTERRUPTED");
			if (stream == TransportByteObserver.Stream.STDOUT) {
				source.put("profile", "claude_stream");
				source.put("route", "sdk_stdio");
				source.put("framing", "lines");
			}
			else {
				source.put("archive_only", true);
			}
			sources.add(source);
		}
		var observation = CaptureFiles.source(directory, directory.resolve("observations.jsonl"), "observations.jsonl",
				"observation_index", connection, invocation);
		observation.put("archive_only", true);
		observation.put("required", true);
		sources.add(observation);
		return sources;
	}

	private static String filename(TransportByteObserver.Stream stream) {
		return stream.name().toLowerCase(java.util.Locale.ROOT) + ".bin";
	}

}
