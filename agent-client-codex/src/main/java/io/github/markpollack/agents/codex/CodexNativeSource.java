/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;

/**
 * Exact observed identity only. Ambiguous, preexisting and mismatched files are refused.
 */
final class CodexNativeSource {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

	private final Set<Path> before;

	private final Path root;

	CodexNativeSource(Path root) throws IOException {
		this.root = root.toAbsolutePath().normalize();
		this.before = Set.copyOf(files());
	}

	private List<Path> files() throws IOException {
		if (!Files.exists(this.root))
			return List.of();
		try (var stream = Files.walk(this.root)) {
			var files = stream.limit(20001).toList();
			if (files.size() > 20000)
				throw new IOException("Native source inventory exceeded limit");
			if (files.stream().anyMatch(Files::isSymbolicLink))
				throw new IOException("Native inventory contains an unobserved symbolic link");
			return files.stream().filter(p -> p.toString().endsWith(".jsonl")).toList();
		}
	}

	Observed read(String nativeId, Path cwd, String version) throws IOException {
		Path file = select(nativeId);
		byte[] bytes = readBytes(file);
		validateHeader(bytes, nativeId, cwd, version);
		return new Observed(file, bytes, turn(bytes));
	}

	private Path select(String nativeId) throws IOException {
		if (nativeId == null || !nativeId.matches("[A-Za-z0-9-]+"))
			throw new IOException("No safe observed native identity");
		var matches = files().stream()
			.filter(p -> p.getFileName().toString().endsWith("-" + nativeId + ".jsonl"))
			.toList();
		if (matches.size() != 1 || this.before.contains(matches.get(0)))
			throw new IOException("Native source is absent, ambiguous or predates dispatch");
		var file = matches.get(0);
		if (outsideRoot(file))
			throw new IOException("Native path escaped its root");
		return file;
	}

	private boolean outsideRoot(Path file) throws IOException {
		return Files.isSymbolicLink(file) || !file.toRealPath().startsWith(this.root.toRealPath());
	}

	private static byte[] readBytes(Path file) throws IOException {
		byte[] bytes;
		try (var stream = Files.newInputStream(file)) {
			bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
		}
		if (bytes.length > 16 * 1024 * 1024)
			throw new IOException("Native source exceeded limit");
		if (bytes.length == 0 || bytes[bytes.length - 1] != '\n')
			throw new IOException("Native source has an incomplete final record");
		return bytes;
	}

	private static void validateHeader(byte[] bytes, String nativeId, Path cwd, String version) throws IOException {
		int end = 0;
		while (end < bytes.length && bytes[end] != '\n')
			end++;
		var record = JSON.readTree(Arrays.copyOf(bytes, end));
		var metadata = record.path("payload");
		if (!"session_meta".equals(record.path("type").asText()) || !nativeId.equals(metadata.path("id").asText())
				|| !cwd.toString().equals(metadata.path("cwd").asText())
				|| !version.equals(metadata.path("cli_version").asText())
				|| !"exec".equals(metadata.path("source").asText()))
			throw new IOException("Native identity, directory or version mismatch");
	}

	private static String turn(byte[] bytes) throws IOException {
		var turns = new HashSet<String>();
		try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.ByteArrayInputStream(bytes), java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				var event = JSON.readTree(line);
				if ("event_msg".equals(event.path("type").asText())
						&& "task_started".equals(event.path("payload").path("type").asText()))
					turns.add(event.path("payload").path("turn_id").asText());
			}
		}
		if (turns.size() != 1 || turns.contains(""))
			throw new IOException("No unambiguous native turn identity");
		return turns.iterator().next();
	}

	static final class Observed {

		private final Path path;

		private final byte[] bytes;

		private final String turnId;

		Observed(Path path, byte[] bytes, String turnId) {
			this.path = path;
			this.bytes = bytes.clone();
			this.turnId = turnId;
		}

		Path path() {
			return this.path;
		}

		byte[] bytes() {
			return this.bytes.clone();
		}

		String turnId() {
			return this.turnId;
		}

	}

}
