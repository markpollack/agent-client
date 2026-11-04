/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thread-level retained manifest written once at connection close: one final transcript
 * (the whole rollout as last read) plus every generation's per-turn originals, usage and
 * byte locators. Per-turn native.bin files remain prefix snapshots, not transcripts.
 */
final class CodexThreadManifest {

	static final String KIND = "journal-codex-retained-generation/1";

	private CodexThreadManifest() {
	}

	static Optional<Path> write(CodexConfiguration configuration, CodexThread thread, List<CodexGeneration> owned) {
		var generations = owned.stream().filter(g -> g.capture != null).toList();
		if (generations.isEmpty())
			return Optional.empty();
		for (var generation : generations)
			generation.capture.observeNative();
		try {
			return Optional.of(writeManifest(configuration, thread, generations));
		}
		catch (IOException | RuntimeException ex) {
			// Secondary evidence never blocks process cleanup; the failure is kept on the thread.
			thread.manifestFailure(String.valueOf(ex.getMessage()));
			return Optional.empty();
		}
	}

	private static Path writeManifest(CodexConfiguration configuration, CodexThread thread,
			List<CodexGeneration> generations) throws IOException {
		var prefix = thread.nativePrefix();
		String nativeId = thread.nativeId().orElse(null);
		Path root = configuration.capture().toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path directory = Files.createTempDirectory(root, "codex-thread-");
		var manifest = new LinkedHashMap<String, Object>();
		manifest.put("kind", KIND);
		manifest.put("native_session_id", nativeId);
		manifest.put("cli_version", configuration.version());
		manifest.put("cwd", generations.get(0).options.getWorkingDirectory().toString());
		manifest.put("native_path", prefix == null ? null : prefix.path().toString());
		manifest.put("sealed", true);
		boolean overflow = false;
		var turns = new ArrayList<Map<String, Object>>();
		for (var generation : generations) {
			try {
				var sealed = generation.capture.seal();
				overflow |= sealed.sources().stream().anyMatch(s -> s.observedBytes() > s.bytes());
				turns.add(turn(generation, sealed));
			}
			catch (RuntimeException ex) {
				turns.add(new LinkedHashMap<>(Map.of("invocation_index", generation.turnIndex, "connection_id",
						generation.id, "state", "UNSEALED", "detail", String.valueOf(ex.getMessage()))));
			}
		}
		manifest.put("overflow", overflow);
		manifest.put("transcript", transcript(directory, prefix));
		manifest.put("turns", turns);
		Path file = directory.resolve("codex-thread.json");
		Files.write(file, new ObjectMapper().writeValueAsBytes(manifest), StandardOpenOption.CREATE_NEW);
		return file;
	}

	/** The final transcript is the rollout as it stands at close, compared with the last sealed prefix. */
	private static Map<String, Object> transcript(Path directory, CodexNativeSource.Prefix prefix) throws IOException {
		var result = new LinkedHashMap<String, Object>();
		if (prefix == null) {
			result.put("state", "ABSENT");
			result.put("observation", "no sealed native observation");
			return result;
		}
		byte[] last = prefix.bytes();
		byte[] current = Files.readAllBytes(prefix.path());
		Path file = directory.resolve("transcript.bin");
		Files.write(file, current, StandardOpenOption.CREATE_NEW);
		result.put("role", "transcript");
		result.put("path", file.getFileName().toString());
		result.put("origin", prefix.path().toString());
		result.put("bytes", current.length);
		result.put("sha256", CodexNativeSource.sha256(current));
		result.put("last_sealed_prefix",
				Map.of("bytes", last.length, "sha256", CodexNativeSource.sha256(last)));
		result.put("final_equals_last_sealed_prefix", Arrays.equals(current, last));
		return result;
	}

	private static Map<String, Object> turn(CodexGeneration generation, CodexAcquisition sealed) throws IOException {
		var turn = new LinkedHashMap<String, Object>();
		var process = generation.process;
		turn.put("invocation_index", generation.turnIndex);
		turn.put("execution_id", sealed.executionId());
		turn.put("connection_id", generation.id);
		turn.put("argv", process.argv());
		turn.put("pids", process.observedPids());
		turn.put("exit_code", generation.exitCode);
		turn.put("resume_thread_id", generation.resumeId);
		turn.put("identity_source", generation.identitySource);
		turn.put("native_turn_uid", null);
		turn.put("complete", sealed.complete());
		turn.put("detail", sealed.detail());
		turn.put("observed_resources_terminated", process.terminated());
		turn.put("sources", sources(turn, sealed));
		turn.put("per_turn_manifest", sealed.directory().resolve("codex-acquisition.json").toString());
		turn.put("wire_usage", generation.wireUsage);
		var observed = generation.capture.nativeObservation();
		if (observed != null) {
			turn.put("native_turn_uid", observed.turnId());
			CodexManifest.nativeFacts(turn, observed);
		}
		return turn;
	}

	/** This process's own originals; the native snapshot is not a per-turn source. */
	private static List<Map<String, Object>> sources(Map<String, Object> turn, CodexAcquisition sealed) {
		var sources = new ArrayList<Map<String, Object>>();
		for (var source : sealed.sources()) {
			if ("native".equals(source.role()))
				continue;
			turn.put(source.role() + (source.role().equals("stdin") ? "_closed" : "_natural_eof"), source.naturalEof());
			sources.add(new LinkedHashMap<>(Map.of("role", source.role().toUpperCase(Locale.ROOT), "path",
					source.path().toString(), "bytes", source.bytes(), "sha256", source.sha256(), "observed",
					source.observedBytes(), "natural_eof", source.naturalEof())));
		}
		return sources;
	}

}
