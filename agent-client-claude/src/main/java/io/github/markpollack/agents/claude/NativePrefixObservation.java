/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** A bounded observation of an exact file, never a prefix inferred after the session. */
final class NativePrefixObservation {

	private static final int LIMIT = 4 * 1024 * 1024;

	private NativePrefixObservation() {
	}

	static Map<String, Object> observe(Path file) {
		if (file == null)
			return Map.of("unavailable", "No exact retained transcript configured");
		try {
			byte[] bytes = read(file);
			return Map.of("bytes", bytes.length, "sha256", hash(bytes));
		}
		catch (IOException | RuntimeException failure) {
			return Map.of("unavailable", failure.toString());
		}
	}

	static byte[] read(Path file) throws IOException {
		var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!before.isRegularFile() || before.size() > LIMIT)
			throw new IOException("Native prefix is not a bounded regular file");
		byte[] bytes;
		try (var input = Files.newInputStream(file)) {
			bytes = input.readNBytes(LIMIT + 1);
		}
		var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (bytes.length != before.size() || bytes.length != after.size()
				|| !before.lastModifiedTime().equals(after.lastModifiedTime())
				|| !Objects.equals(before.fileKey(), after.fileKey()))
			throw new IOException("Native prefix changed during observation");
		return bytes;
	}

	static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static List<Map<String, Object>> copy(Path original, Path directory, String session) throws IOException {
		Path owned = original.getParent().resolve(session == null ? "" : session);
		if (Files.isDirectory(owned)) {
			try (var paths = Files.walk(owned)) {
				if (paths.anyMatch(Files::isRegularFile))
					throw new IOException("Native child/sidecar capture is outside the retained text profile");
			}
		}
		byte[] bytes = read(original);
		Path target = directory.resolve("native/transcript.jsonl");
		Files.createDirectory(target.getParent());
		Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
		return List.of(Map.of("role", "transcript", "path", "native/transcript.jsonl", "origin", original.toString(),
				"sha256", hash(bytes), "bytes", bytes.length, "state", "acquired", "end", "snapshot_extent",
				"membership", "generation_snapshot_not_execution_attribution"));
	}

}
