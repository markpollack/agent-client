/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Caller observations for the independent Journal qualifier; no canonical interpretation.
 */
final class CodexManifest {

	private CodexManifest() {
	}

	record Context(CodexGeneration connection, String execution, Instant start) {
	}

	static String template(Context context, List<CodexAcquisition.Source> sources,
			CodexNativeSource.Observed nativeSource, boolean complete, String detail) throws IOException {
		var connection = context.connection();
		String execution = context.execution();
		var root = object("kind", "agent-journal/acquired-execution", "version", "0.1", "profile",
				"codex-cli-0.166.0-exec-stdio-rollout", "profile_id", "codex-cli/0.166.0/exec-stdio+rollout",
				"acquisition_uid", execution, "complete", complete, "capture_errors",
				complete ? List.of() : List.of(detail));
		root.put("process", process(connection, sources, nativeSource));
		root.put("run",
				object("run_uid", execution, "session_uid", connection.nativeId, "capture_start",
						context.start().toString(), "invocation_index", 0, "cli",
						object("name", "Codex CLI", "vendor_name", "OpenAI", "version",
								connection.configuration.version()),
						"launch_settings", object("route", "exec_stdio", "working_directory",
								connection.options.getWorkingDirectory().toString())));
		var entries = new ArrayList<Map<String, Object>>();
		for (var source : sources)
			entries.add(source(source, execution, connection.nativeId, nativeSource));
		root.put("sources", entries);
		var ends = new LinkedHashMap<String, Long>();
		for (var source : sources)
			if (!"native".equals(source.role()))
				ends.put(source.role().toUpperCase(Locale.ROOT), source.bytes());
		root.put("turns",
				List.of(object("invocation_uid", execution, "decision_uid", null, "native_turn_uid",
						nativeSource == null ? null : nativeSource.turnId(), "start_offsets",
						Map.of("STDIN", 0, "STDOUT", 0, "STDERR", 0), "end_offsets", ends)));
		return new ObjectMapper().writeValueAsString(root);
	}

	private static Map<String, Object> process(CodexGeneration connection, List<CodexAcquisition.Source> sources,
			CodexNativeSource.Observed nativeSource) {
		var process = connection.process;
		return object("argv", process.argv(), "cwd", connection.options.getWorkingDirectory().toString(),
				"connection_uid", connection.id, "client_session_uid", null, "exit_code", connection.exitCode, "sealed",
				true, "fresh_session", true, "native_path_preexisting", nativeSource == null ? null : false,
				"observed_resources_terminated", process.terminated(), "stdin_closed", ended(sources, "stdin"),
				"stdout_natural_eof", ended(sources, "stdout"), "stderr_natural_eof", ended(sources, "stderr"),
				"overflow", sources.stream().anyMatch(source -> source.observedBytes() > source.bytes()));
	}

	private static boolean ended(List<CodexAcquisition.Source> sources, String role) {
		return sources.stream().anyMatch(source -> source.role().equals(role) && source.naturalEof());
	}

	private static Map<String, Object> source(CodexAcquisition.Source source, String execution, String nativeId,
			CodexNativeSource.Observed nativeSource) throws IOException {
		String role = role(source.role());
		String file = source.path().getFileName().toString();
		boolean wire = "stream".equals(role), nativeFile = "transcript".equals(role);
		var result = object("role", role, "path", file, "artifact", file, "origin",
				nativeFile ? nativeSource.path().toString() : "codex-" + source.role(), "required", true,
				"archive_only", !wire && !nativeFile, "state", "acquired", "bytes", source.bytes(), "sha256",
				source.sha256(), "observed", source.observedBytes(), "dropped", source.observedBytes() - source.bytes(),
				"end", source.naturalEof() ? "EOF" : "UNKNOWN", "acquisition", execution);
		if (wire || nativeFile) {
			result.put("profile", wire ? "codex_stream" : "codex_rollout");
			result.put("route", wire ? "stdout" : "native_session_file");
			result.put("framing", "lines");
			result.put("records", records(source));
		}
		if (nativeFile)
			result.put("owner_session_uid", nativeId);
		return result;
	}

	private static String role(String role) {
		return switch (role) {
			case "stdout" -> "stream";
			case "native" -> "transcript";
			default -> role;
		};
	}

	private static long records(CodexAcquisition.Source source) throws IOException {
		long records = 0;
		for (byte value : Files.readAllBytes(source.path()))
			if (value == '\n')
				records++;
		return records;
	}

	private static Map<String, Object> object(Object... pairs) {
		var result = new LinkedHashMap<String, Object>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}

}
