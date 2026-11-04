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
		return new Observed(file, bytes, 0, turn(bytes, 0));
	}

	/**
	 * A resumed turn appends to the exact file observed before: the earlier bytes must be
	 * an unchanged prefix and the appended region must carry exactly one new turn.
	 */
	Observed readContinuation(Prefix previous, String nativeId, Path cwd, String version) throws IOException {
		Path file = previous.path();
		if (outsideRoot(file))
			throw new IOException("Native path escaped its root");
		byte[] bytes = readBytes(file);
		byte[] before = previous.bytes();
		if (bytes.length <= before.length || !Arrays.equals(bytes, 0, before.length, before, 0, before.length))
			throw new IOException("Native source prefix changed or did not grow after resume");
		validateHeader(bytes, nativeId, cwd, version);
		return new Observed(file, bytes, before.length, turn(bytes, before.length));
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

	private static String turn(byte[] bytes, int regionStart) throws IOException {
		var turns = new HashSet<String>();
		try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.ByteArrayInputStream(bytes, regionStart, bytes.length - regionStart),
				java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				var event = JSON.readTree(line);
				if ("event_msg".equals(event.path("type").asText())
						&& "task_started".equals(event.path("payload").path("type").asText()))
					turns.add(event.path("payload").path("turn_id").asText());
				if (event.path("payload").path("forked_from_id").isTextual())
					throw new IOException("Native source is a forked thread");
			}
		}
		if (turns.size() != 1 || turns.contains(""))
			throw new IOException("No unambiguous native turn identity");
		return turns.iterator().next();
	}

	static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Exact observed file and its bytes at the end of an earlier turn. */
	static final class Prefix {

		private final Path path;

		private final byte[] bytes;

		Prefix(Path path, byte[] bytes) {
			this.path = path;
			this.bytes = bytes.clone();
		}

		Path path() {
			return this.path;
		}

		byte[] bytes() {
			return this.bytes.clone();
		}

	}

	static final class Observed {

		private final Path path;

		private final byte[] bytes;

		private final String turnId;

		private final int regionStart;

		Observed(Path path, byte[] bytes, int regionStart, String turnId) {
			this.path = path;
			this.bytes = bytes.clone();
			this.regionStart = regionStart;
			this.turnId = turnId;
		}

		int regionStart() {
			return this.regionStart;
		}

		/** Zero-based index of the first record appended by this turn. */
		long firstLine() {
			return lines(0, this.regionStart);
		}

		long lastLine() {
			return lines(0, this.bytes.length) - 1;
		}

		private long lines(int from, int to) {
			long count = 0;
			for (int i = from; i < to; i++)
				if (this.bytes[i] == '\n')
					count++;
			return count;
		}

		/** Last cumulative token_count snapshot inside this turn's region, if any. */
		Map<String, Object> cumulativeUsage() throws IOException {
			Map<String, Object> usage = Map.of();
			try (var reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(new java.io.ByteArrayInputStream(this.bytes, this.regionStart,
							this.bytes.length - this.regionStart), java.nio.charset.StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					var event = JSON.readTree(line);
					var total = event.path("payload").path("info").path("total_token_usage");
					if ("token_count".equals(event.path("payload").path("type").asText()) && total.isObject())
						usage = JSON.convertValue(total,
								new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
								});
				}
			}
			return usage;
		}

		/**
		 * Whole-line byte locators of every record appended by this turn; the shared
		 * session_meta line is never a turn record.
		 */
		List<Map<String, Object>> records() {
			return locators(Math.max(this.regionStart, sharedEnd()), this.bytes.length);
		}

		/** The single pre-turn-1 session_meta line shared by every turn. */
		List<Map<String, Object>> shared() {
			return locators(0, sharedEnd());
		}

		private int sharedEnd() {
			int end = 0;
			while (end < this.bytes.length && this.bytes[end] != '\n')
				end++;
			return Math.min(end + 1, this.bytes.length);
		}

		private List<Map<String, Object>> locators(int from, int to) {
			var result = new ArrayList<Map<String, Object>>();
			int start = from;
			for (int i = from; i < to; i++) {
				if (this.bytes[i] == '\n') {
					var locator = new LinkedHashMap<String, Object>();
					locator.put("source", "TRANSCRIPT");
					locator.put("start", start);
					locator.put("end", i + 1);
					locator.put("sha256", sha256(Arrays.copyOfRange(this.bytes, start, i + 1)));
					result.add(locator);
					start = i + 1;
				}
			}
			return result;
		}

		/**
		 * The token_usage_record inside this turn's region: per-turn and cumulative thread
		 * usage as the CLI wrote them, never summed by Client.
		 */
		Map<String, Map<String, Object>> turnUsage() throws IOException {
			var result = new LinkedHashMap<String, Map<String, Object>>();
			try (var reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(new java.io.ByteArrayInputStream(this.bytes, this.regionStart,
							this.bytes.length - this.regionStart), java.nio.charset.StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					var event = JSON.readTree(line);
					if (!"token_usage_record".equals(event.path("type").asText()))
						continue;
					var payload = event.path("payload");
					for (var key : List.of("turn_token_usage", "thread_token_usage"))
						if (payload.path(key).isObject())
							result.put(key, JSON.convertValue(payload.get(key),
									new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
									}));
				}
			}
			return result;
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
