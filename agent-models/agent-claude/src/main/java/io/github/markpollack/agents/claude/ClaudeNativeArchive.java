/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Copies only the observed session's files; never chooses the newest transcript. */
final class ClaudeNativeArchive {

	private ClaudeNativeArchive() {
	}

	static List<Map<String, Object>> acquire(ClaudeCaptureOptions options, String sessionId) throws IOException {
		if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]+")) {
			return List.of(CaptureFiles.missing("transcript.jsonl", "transcript", false,
					"No safe observed native session identity"));
		}
		Path root = options.nativeProjectsDirectory();
		if (!Files.isDirectory(root)) {
			return List.of(CaptureFiles.missing("transcript.jsonl", "transcript", false,
					"Native projects directory unavailable"));
		}
		List<Path> matches;
		try (var paths = Files.walk(root, 2)) {
			matches = paths.filter(p -> p.getFileName().toString().equals(sessionId + ".jsonl"))
				.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
				.toList();
		}
		if (matches.size() != 1) {
			return List.of(CaptureFiles.missing("transcript.jsonl", "transcript", false,
					"Exact session lookup yielded " + matches.size() + " files"));
		}
		Path transcript = matches.get(0);
		verifySession(transcript, sessionId);
		List<Map<String, Object>> sources = new ArrayList<>();
		var source = copy(options, transcript, Path.of("native", "transcript.jsonl"), "transcript", sessionId);
		source.put("artifact", "transcript.jsonl");
		source.put("profile", "claude_transcript");
		source.put("route", "native_session_file");
		source.put("framing", "lines");
		source.put("records", CaptureFiles.records(options.directory().resolve("native/transcript.jsonl")));
		source.put("required", false);
		sources.add(source);
		sources.addAll(acquireOwned(options, transcript, sessionId));
		return sources;
	}

	private static List<Map<String, Object>> acquireOwned(ClaudeCaptureOptions options, Path transcript,
			String sessionId) throws IOException {
		List<Map<String, Object>> sources = new ArrayList<>();

		Path owned = transcript.getParent().resolve(sessionId);
		if (Files.isDirectory(owned, LinkOption.NOFOLLOW_LINKS)) {
			try (var files = Files.walk(owned)) {
				for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
					.sorted()
					.toList()) {
					Path relative = owned.relativize(file);
					boolean child = relative.startsWith("subagents") && file.toString().endsWith(".jsonl");
					if (child) {
						verifySession(file, sessionId);
					}
					var extra = copy(options, file, Path.of("native", sessionId).resolve(relative),
							child ? "child_transcript" : "sidecar", sessionId);
					extra.put("archive_only", true);
					extra.put("required", true);
					extra.put("membership_basis",
							child ? "exact_parent_session_directory_and_sessionId" : "exact_parent_session_directory");
					sources.add(extra);
				}
			}
		}
		return sources;
	}

	private static void verifySession(Path file, String sessionId) throws IOException {
		boolean witnessed = false;
		try (var reader = Files.newBufferedReader(file)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				var record = CaptureFiles.JSON.readTree(line);
				var id = record.get("sessionId");
				if (id != null && !id.isNull()) {
					if (!sessionId.equals(id.asText())) {
						throw new IOException("Native session identity mismatch: " + file);
					}
					witnessed = true;
				}
			}
		}
		if (!witnessed) {
			throw new IOException("Native session identity not witnessed: " + file);
		}
	}

	private static Map<String, Object> copy(ClaudeCaptureOptions options, Path source, Path relative, String role,
			String owner) throws IOException {
		var before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		Path target = options.directory().resolve(relative);
		Files.createDirectories(target.getParent());
		Files.copy(source, target);
		var after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
				|| !CaptureFiles.hash(source).equals(CaptureFiles.hash(target))) {
			throw new IOException("Native source changed while acquiring: " + source);
		}
		var entry = CaptureFiles.source(options.directory(), target, relative.toString(), role,
				source.toAbsolutePath().toString(), options.invocationId());
		entry.put("owner_session_uid", owner);
		entry.put("end", "EOF");
		entry.put("end_basis", "Copied file extent stable across acquisition; not proof of future native quiescence");
		return entry;
	}

}
