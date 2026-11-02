/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/** One invocation's acquisition lifetime. Sealing follows SDK client close. */
final class ClaudeCaptureSession {

	private final ClaudeCaptureOptions options;

	private final ClaudeWireCapture wire;

	private final Instant started;

	private final String connection;

	private volatile Map<String, Object> observedProcess = Map.of();

	ClaudeCaptureSession(ClaudeCaptureOptions options) {
		this(options, UUID.randomUUID().toString());
	}

	ClaudeCaptureSession(ClaudeCaptureOptions options, String connection) {
		this(options, connection, Instant.now());
	}

	ClaudeCaptureSession(ClaudeCaptureOptions options, String connection, Instant started) {
		this.started = started;
		this.connection = connection;
		this.options = options;
		try {
			Files.createDirectories(options.directory().getParent());
			Files.createDirectory(options.directory());
			if (Files.getFileStore(options.directory()).supportsFileAttributeView("posix")) {
				Files.setPosixFilePermissions(options.directory(),
						java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			}
			wire = new ClaudeWireCapture(options.directory());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot create acquisition/trace directory", ex);
		}
	}

	ClaudeWireCapture wire() {
		return wire;
	}

	synchronized void observeProcess(Map<String, Object> process) {
		this.observedProcess = Map.copyOf(process);
	}

	synchronized void observeProcess() {
		if (!observedProcess.isEmpty()) {
			return;
		}
		try (var children = ProcessHandle.current().children()) {
			var matches = children
				.filter(p -> p.info()
					.arguments()
					.map(args -> java.util.Arrays.asList(args).contains("stream-json"))
					.orElse(false))
				.toList();
			if (matches.size() == 1) {
				var process = matches.get(0);
				var info = process.info();
				List<String> argv = new ArrayList<>();
				info.command().ifPresent(argv::add);
				info.arguments().ifPresent(args -> argv.addAll(List.of(args)));
				observedProcess = Map.of("pid", process.pid(), "argv", List.copyOf(argv), "basis",
						"unique_direct_child_with_stream_json_arguments", "observed_at", Instant.now().toString());
			}
		}
	}

	ClaudeAcquisition seal(ClaudeExecutionResult result, String failure, CLIOptions cli, Path cwd, String prompt) {
		return seal(result, failure, cli, cwd, prompt, false);
	}

	ClaudeAcquisition seal(ClaudeExecutionResult result, String failure, CLIOptions cli, Path cwd, String prompt,
			boolean requireTranscript) {
		wire.close();
		List<String> errors = new ArrayList<>(wire.failures());
		if (failure != null) {
			errors.add(failure);
		}
		if (!result.terminalReceived()) {
			errors.add("No terminal SDK result observed");
		}
		try {
			List<Map<String, Object>> nativeSources;
			try {
				nativeSources = ClaudeNativeArchive.acquire(options, result.sessionId());
			}
			catch (IOException ex) {
				errors.add(ex.toString());
				nativeSources = List.of(CaptureFiles.missing("transcript.jsonl", "transcript", true, ex.toString()));
			}
			if (requireTranscript && nativeSources.stream()
				.noneMatch(source -> "transcript".equals(source.get("role")) && "acquired".equals(source.get("state"))))
				errors.add("Native transcript unavailable");
			boolean complete = errors.isEmpty();
			List<Map<String, Object>> sources = wire.sources(connection, options.invocationId(), complete);
			sources.addAll(nativeSources);
			Map<String, Object> manifest = manifest(result, failure, cli, cwd, prompt, errors);
			manifest.put("sources", sources);
			Path path = options.directory().resolve("acquisition.json");
			Files.writeString(path, CaptureFiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
					java.nio.file.StandardOpenOption.CREATE_NEW);
			return new ClaudeAcquisition(path, CaptureFiles.hash(path), complete, options.invocationId(),
					result.sessionId());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot seal acquisition; retained files at " + options.directory(), ex);
		}
	}

	private Map<String, Object> manifest(ClaudeExecutionResult result, String failure, CLIOptions cli, Path cwd,
			String prompt, List<String> errors) {

		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("kind", "agent-journal/acquired-execution");
		manifest.put("version", "0.1");
		manifest.put("profile", "claude-code-" + options.cliVersion() + "-sdk-stdio-session-file");
		manifest.put("profile_id", "claude-code/" + options.cliVersion() + "/sdk-stdio+session-file");
		manifest.put("acquisition_uid", options.invocationId());
		manifest.put("complete", errors.isEmpty());
		manifest.put("run", run(result, cli, cwd, prompt));
		manifest.put("process", process(result, failure));
		manifest.put("capture_errors", errors);
		manifest.put("turns",
				List.of(Map.of("invocation_uid", options.invocationId(), "index", 0, "start", started.toString(), "end",
						Instant.now().toString(), "start_offsets", Map.of("STDIN", 0, "STDOUT", 0, "STDERR", 0),
						"end_offsets", wire.offsets())));
		return manifest;
	}

	private Map<String, Object> run(ClaudeExecutionResult result, CLIOptions cli, Path cwd, String prompt) {
		Map<String, Object> launch = new LinkedHashMap<>();
		launch.put("model", cli.getModel() == null ? "CLI default" : cli.getModel());
		launch.put("route", "sdk_stdio");
		launch.put("cwd", cwd.toAbsolutePath().normalize().toString());
		launch.put("argv", observedProcess.getOrDefault("argv", List.of()));
		launch.put("argv_basis", observedProcess.isEmpty() ? "unavailable" : "observed_ProcessHandle");
		launch.put("timeout_ms", cli.timeout() == null ? 0 : cli.timeout().toMillis());
		launch.put("permission_mode", cli.getPermissionMode().getValue());
		launch.put("caller_input", Map.of("prompt", prompt, "provenance", "caller-supplied"));
		launch.put("artifact_provenance", options.provenance());
		launch.put("sdk_version", "1.8.0-SNAPSHOT");
		launch.put("client_version", ClaudeArtifactVersion.version());
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("run_uid", options.invocationId());
		run.put("invocation_index", 0);
		if (result.sessionId() != null) {
			run.put("session_uid", result.sessionId());
		}
		run.put("capture_start", started.toString());
		run.put("cli", Map.of("name", "Claude Code", "version", options.cliVersion(), "vendor_name", "Anthropic",
				"version_basis", "caller-observed CLI version; raw init retained for verification"));
		run.put("launch_settings", launch);
		return run;
	}

	private Map<String, Object> process(ClaudeExecutionResult result, String failure) {
		Map<String, Object> process = new LinkedHashMap<>(observedProcess);
		process.put("connection_uid", connection);
		process.put("status", failure != null ? "sdk_failed_or_interrupted"
				: result.terminalReceived() ? "sdk_closed_after_result" : "sdk_closed_without_result");
		process.put("execution_error", failure);
		process.put("cancellation", "not_independently_exposed_by_sdk");
		process.put("timeout_outcome", "not_independently_exposed_by_sdk; inspect execution_error");
		process.put("exit_code", null);
		process.put("exit_code_basis", "SDK does not expose process exit receipt");
		process.put("terminal_received", result.terminalReceived());
		process.put("reported_success", result.successful());
		process.put("closed_at", Instant.now().toString());
		return process;
	}

}
