/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Ordered observations of one fresh-start native transcript: absence probes and a
 * candidate scan before the first dispatch, each dispatch's wire offsets, readable
 * prefixes after dispatch and one final enumeration. Every line is what this process saw
 * at that moment; Journal checks the sequence for consistency and decides.
 */
final class FreshStartObserver {

	static final Map<String, Object> ABSENT_BEFORE_FIRST_DISPATCH = Map.of("state", "ABSENT", "observation",
			"before-first-dispatch");

	private final Path root;

	private final Path cwd;

	private final String sessionId;

	private final Path expected;

	private final List<Map<String, Object>> journal = new ArrayList<>();

	private long seq;

	private long nanos = System.nanoTime();

	private boolean dispatched;

	private boolean finished;

	private String firstProbe;

	private long firstDispatchStdout = -1;

	private List<String> argv = List.of();

	FreshStartObserver(Path nativeProjectsRoot, Path cwd, String sessionId) {
		this.root = nativeProjectsRoot.toAbsolutePath().normalize();
		this.cwd = cwd.toAbsolutePath().normalize();
		this.sessionId = sessionId;
		this.expected = this.root.resolve(encode(this.cwd.toString())).resolve(sessionId + ".jsonl");
	}

	/**
	 * The CLI names a project directory by replacing every non-alphanumeric character.
	 */
	static String encode(String directory) {
		var encoded = new StringBuilder(directory.length());
		for (int i = 0; i < directory.length(); i++) {
			char c = directory.charAt(i);
			encoded.append(Character.isLetterOrDigit(c) && c < 128 ? c : '-');
		}
		return encoded.toString();
	}

	String sessionId() {
		return this.sessionId;
	}

	Path expected() {
		return this.expected;
	}

	synchronized void argv(List<String> observed) {
		if (this.argv.isEmpty())
			this.argv = List.copyOf(observed);
	}

	/** Before dispatch only: the expected file must not exist. */
	synchronized void probe() {
		if (this.dispatched || this.finished)
			return;
		String result;
		try {
			Files.readAttributes(this.expected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			result = "EXISTS";
		}
		catch (NoSuchFileException ex) {
			result = "ENOENT";
		}
		catch (IOException | RuntimeException ex) {
			result = ex.getClass().getSimpleName();
		}
		if (this.firstProbe == null)
			this.firstProbe = result;
		Path parent = this.expected.getParent();
		record("probe", entries("path", this.expected.toString(), "result", result, "parentPath", parent.toString(),
				"parentFileKey", fileKey(parent)));
	}

	/**
	 * Before dispatch only: no file anywhere under the root may already carry the
	 * identity.
	 */
	synchronized void scan() {
		if (this.dispatched || this.finished)
			return;
		var scan = new Scan();
		record("scan", entries("root", this.root.toString(), "sessionId", this.sessionId, "matches", scan.matches,
				"skipped", scan.skipped, "unreadable", scan.unreadable));
	}

	synchronized void dispatch(String executionId, long stdinOffset, long stdoutOffset) {
		if (this.finished)
			return;
		if (!this.dispatched)
			this.firstDispatchStdout = stdoutOffset;
		this.dispatched = true;
		record("dispatch",
				entries("executionId", executionId, "stdinOffset", stdinOffset, "stdoutOffset", stdoutOffset));
	}

	/**
	 * The first turn's before-boundary is the tagged absence, never an invented empty
	 * prefix.
	 */
	synchronized Map<String, Object> before() {
		if (!this.dispatched && "ENOENT".equals(this.firstProbe))
			return ABSENT_BEFORE_FIRST_DISPATCH;
		return prefix();
	}

	/** After dispatch: a readable whole-file prefix with stable file identity. */
	synchronized Map<String, Object> prefix() {
		if (this.finished)
			return Map.of("unavailable", "observation journal already final");
		try {
			byte[] bytes = NativePrefixObservation.read(this.expected);
			String hash = NativePrefixObservation.hash(bytes);
			if (this.dispatched)
				record("prefix", entries("path", this.expected.toString(), "fileKey", fileKey(this.expected), "bytes",
						bytes.length, "sha256", hash));
			return Map.of("bytes", bytes.length, "sha256", hash);
		}
		catch (IOException | RuntimeException failure) {
			return Map.of("unavailable", failure.toString());
		}
	}

	/**
	 * Final enumeration and copy from the same read; the journal is then closed and
	 * written beside the originals.
	 */
	synchronized Map<String, Object> finish(Path directory, byte[] stdout, String connectionId) throws IOException {
		if (this.finished)
			throw new IllegalStateException("Fresh-start observation already final");
		var result = new LinkedHashMap<String, Object>();
		result.put("freshStart",
				entries("requestedSessionId", this.sessionId, "expectedNativePath", this.expected.toString(),
						"launchArgv", this.argv, "processIdentity", connectionId, "cwd", this.cwd.toString()));
		result.put("observedNativeIdentity", identity(stdout));
		byte[] bytes = NativePrefixObservation.read(this.expected);
		var scan = new Scan();
		record("final",
				entries("path", this.expected.toString(), "fileKey", fileKey(this.expected), "bytes", bytes.length,
						"sha256", NativePrefixObservation.hash(bytes), "candidates", scan.matches, "skipped",
						scan.skipped, "unreadable", scan.unreadable));
		this.finished = true;
		result.put("nativeFiles", NativePrefixObservation.copy(this.expected, bytes, directory, this.sessionId));
		var lines = new StringBuilder();
		for (var line : this.journal)
			lines.append(CaptureFiles.JSON.writeValueAsString(line)).append('\n');
		byte[] journalBytes = lines.toString().getBytes(StandardCharsets.UTF_8);
		Path file = directory.resolve("observations.jsonl");
		Files.write(file, journalBytes, StandardOpenOption.CREATE_NEW);
		result.put("observations", entries("path", file.getFileName().toString(), "sha256",
				NativePrefixObservation.hash(journalBytes), "size", journalBytes.length));
		return result;
	}

	/**
	 * The first whole stdout record at or after the first dispatch whose session_id is
	 * the requested identity and whose type the CLI only emits for a live session.
	 */
	private Map<String, Object> identity(byte[] stdout) {
		if (this.firstDispatchStdout < 0)
			return null;
		for (var record : RetainedRecord.read("STDOUT", stdout)) {
			if (record.start() < this.firstDispatchStdout
					|| !Set.of("system", "assistant", "result").contains(record.type()))
				continue;
			if (!this.sessionId.equals(record.value().path("session_id").asText()))
				continue;
			var locator = new LinkedHashMap<>(record.locator());
			locator.put("reportedSessionId", this.sessionId);
			locator.put("recordType", record.type());
			return locator;
		}
		return null;
	}

	private void record(String kind, Map<String, Object> members) {
		this.nanos = Math.max(this.nanos + 1, System.nanoTime());
		var line = new LinkedHashMap<String, Object>();
		line.put("seq", ++this.seq);
		line.put("monotonicNanos", this.nanos);
		line.put("kind", kind);
		line.putAll(members);
		this.journal.add(line);
	}

	private static Map<String, Object> entries(Object... pairs) {
		var map = new LinkedHashMap<String, Object>();
		for (int i = 0; i < pairs.length; i += 2)
			map.put((String) pairs[i], pairs[i + 1]);
		return map;
	}

	/**
	 * A file identity, or the honest reason none exists; never an empty or invented
	 * identity.
	 */
	private static String fileKey(Path path) {
		try {
			Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
			return key == null ? "UNAVAILABLE" : key.toString();
		}
		catch (NoSuchFileException ex) {
			return "ENOENT";
		}
		catch (IOException ex) {
			return ex.getClass().getSimpleName();
		}
	}

	/**
	 * Exhaustive candidate enumeration under the root; failures are counted, never
	 * hidden.
	 */
	private final class Scan extends SimpleFileVisitor<Path> {

		final List<String> matches = new ArrayList<>();

		int skipped;

		int unreadable;

		Scan() {
			if (!Files.isDirectory(FreshStartObserver.this.root))
				return;
			try {
				Files.walkFileTree(FreshStartObserver.this.root, EnumSet.noneOf(FileVisitOption.class), 3, this);
			}
			catch (IOException ex) {
				this.unreadable++;
			}
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
			String name = file.getFileName().toString();
			if (!attributes.isRegularFile() && !attributes.isDirectory())
				this.skipped++;
			else if (attributes.isRegularFile() && name.equals(FreshStartObserver.this.sessionId + ".jsonl"))
				this.matches.add(file.toString());
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException failure) {
			this.unreadable++;
			return FileVisitResult.CONTINUE;
		}

	}

}
