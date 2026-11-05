/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.assertj.core.api.Assertions.*;

class ClaudeAgentModelTraceDirTest {

	@TempDir
	Path temp;

	@Test
	void wireRetainsInvalidUtf8CrLfAndFinalFragmentsAndLatchesLateWrites() throws Exception {
		var wire = new ClaudeWireCapture(temp);
		byte[] first = { 'a', '\r', '\n', (byte) 0xff };
		wire.onBytes(Stream.STDOUT, first);
		first[0] = 'x';
		wire.onBytes(Stream.STDOUT, new byte[] { 'z' });
		wire.onBytes(Stream.STDIN, new byte[] { 'p', '\n' });
		wire.onBytes(Stream.STDERR, new byte[] { 'e' });
		wire.close();
		assertThat(Files.readAllBytes(temp.resolve("stdout.bin"))).containsExactly((byte) 'a', (byte) '\r', (byte) '\n',
				(byte) 0xff, (byte) 'z');
		assertThat(Files.readString(temp.resolve("stdin.bin"))).isEqualTo("p\n");
		assertThat(Files.readString(temp.resolve("stderr.bin"))).isEqualTo("e");
		assertThat(wire.offsets()).containsEntry("STDOUT", 5L);
		assertThatThrownBy(() -> wire.onBytes(Stream.STDERR, new byte[] { 1 })).isInstanceOf(java.io.IOException.class);
		assertThat(wire.failures()).hasSize(1);
		var sources = wire.sources("connection", "invocation", false);
		assertThat(sources).hasSize(4);
		assertThat(sources.stream().filter(s -> "stream".equals(s.get("role"))).findFirst().orElseThrow())
			.containsEntry("records", 2L);
		assertThat(sources.get(3)).containsEntry("end", "INTERRUPTED");
		assertThat(Files.readAllLines(temp.resolve("observations.jsonl"))).hasSize(4);
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void realSdkFakeChildSealsExactSourcesAndNativeMembership() throws Exception {
		Path nativeDir = nativeTranscript("session-a", "session-a");
		Path child = nativeDir.resolve("project/session-a/subagents/agent-child.jsonl");
		Files.createDirectories(child.getParent());
		Files.writeString(child, "{\"sessionId\":\"session-a\",\"agentId\":\"child\",\"type\":\"assistant\"}\n");
		Path sidecar = nativeDir.resolve("project/session-a/tool-results/tool-a.txt");
		Files.createDirectories(sidecar.getParent());
		Files.writeString(sidecar, "full result");
		var response = execute("session-a", false, nativeDir, temp.resolve("package"));
		assertThat(response.getResult().getOutput()).isEqualTo("sentinel-answer");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
		assertThat(response.getMetadata().<Object>get("phaseCapture")).isNotNull();
		var acquisition = ClaudeAcquisition.from(response);
		assertThat(acquisition.complete()).isTrue();
		assertThat(acquisition.sha256()).isEqualTo(CaptureFiles.hash(acquisition.manifest()));
		var manifest = CaptureFiles.JSON.readTree(acquisition.manifest().toFile());
		assertThat(manifest.path("run").path("session_uid").asText()).isEqualTo("session-a");
		assertThat(manifest.path("run").path("launch_settings").path("client_version").asText()).isEqualTo("unknown");
		assertThat(manifest.path("process").path("exit_code").isNull()).isTrue();
		assertThat(manifest.path("sources").size()).isEqualTo(7);
		assertThat(Files.readString(temp.resolve("package/native/transcript.jsonl"))).contains("session-a");
		assertThat(Files.readString(temp.resolve("package/stdout.bin"))).contains("unknown_event");
		assertThat(Files.readString(temp.resolve("package/stdin.bin"))).contains("find sentinel");
		assertThat(Files.readString(temp.resolve("package/stderr.bin"))).isEqualTo("stderr-tail");
		for (JsonNode source : manifest.path("sources")) {
			assertThat(source.path("end").asText()).isEqualTo("EOF");
			assertThat(source.path("end_basis").asText()).isNotBlank();
			if ("lines".equals(source.path("framing").asText())) {
				assertThat(source.path("records").asLong())
					.isEqualTo("stream".equals(source.path("role").asText()) ? 3L : 1L);
			}
			assertThat(source.path("sha256").asText())
				.isEqualTo(CaptureFiles.hash(temp.resolve("package").resolve(source.path("path").asText())));
		}
		assertThatThrownBy(() -> execute("session-a", false, nativeDir, temp.resolve("package")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("directory");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void wrongNativeSessionIsRejectedWhileRawBytesSurvive() throws Exception {
		var response = execute("session-a", false, nativeTranscript("session-a", "wrong-session"),
				temp.resolve("package"));
		var acquisition = ClaudeAcquisition.from(response);
		assertThat(acquisition.complete()).isFalse();
		assertThat(Files.readString(acquisition.manifest())).contains("identity mismatch");
		assertThat(Files.size(temp.resolve("package/stdout.bin"))).isPositive();
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void missingExactSessionDoesNotChooseNewestAndErrorResultIsNotSuccess() throws Exception {
		var response = execute("absent", true, nativeTranscript("unrelated", "unrelated"), temp.resolve("package"));
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("ERROR");
		var acquisition = ClaudeAcquisition.from(response);
		assertThat(acquisition.complete()).isTrue();
		assertThat(Files.readString(acquisition.manifest())).contains("Exact session lookup yielded 0 files");
		assertThat(temp.resolve("package/native/transcript.jsonl")).doesNotExist();
	}

	@Test
	void failedLaunchRetainsIncompletePackage() throws Exception {
		var options = new ClaudeCaptureOptions(temp.resolve("package"), "failed", "2.1.316", temp.resolve("absent"),
				Map.of());
		try (var model = ClaudeAgentModel.builder()
			.capture(options)
			.claudePath(temp.resolve("missing-cli").toString())
			.build()) {
			var response = model.call(new AgentTaskRequest("test", temp, null));
			assertThat(ClaudeAcquisition.from(response).complete()).isFalse();
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("ERROR");
		}
	}

	@Test
	void nativeLookupRefusesUnsafeUnknownAndAmbiguousIdentities() throws Exception {
		var options = new ClaudeCaptureOptions(temp.resolve("package"), "id", "2.1.316", temp.resolve("native"),
				Map.of());
		assertThat(ClaudeNativeArchive.acquire(options, "../bad").get(0).get("state")).isEqualTo("missing");
		assertThat(ClaudeNativeArchive.acquire(options, "missing").get(0).get("state")).isEqualTo("missing");
		Path nativeDir = nativeTranscript("session-a", "session-a");
		Files.createDirectories(nativeDir.resolve("other"));
		Files.copy(nativeDir.resolve("project/session-a.jsonl"), nativeDir.resolve("other/session-a.jsonl"));
		assertThat(ClaudeNativeArchive.acquire(options, "session-a").get(0).get("reason")).asString()
			.contains("2 files");
	}

	@Test
	void framedRecordCountsPreserveEmptyAndUnterminatedExtents() throws Exception {
		Path source = temp.resolve("frames");
		Files.write(source, new byte[0]);
		assertThat(CaptureFiles.records(source)).isZero();
		Files.write(source, new byte[] { '\n', 'a', '\r', '\n', (byte) 0xff });
		assertThat(CaptureFiles.records(source)).isEqualTo(3L);
		Files.writeString(source, "a\n");
		assertThat(CaptureFiles.records(source)).isEqualTo(1L);
	}

	@Test
	void optionsValidateAndCopyProvenance() {
		assertThatThrownBy(() -> new ClaudeCaptureOptions(temp, "", "2.1.316"))
			.isInstanceOf(IllegalArgumentException.class);
		var map = new java.util.HashMap<String, String>();
		map.put("commit", "exact");
		var options = new ClaudeCaptureOptions(temp, "id", "2.1.316", temp, map);
		map.put("commit", "changed");
		assertThat(options.provenance()).containsEntry("commit", "exact");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void traceDirAloneKeepsTheDerivedTraceAndPhaseCaptureSurface() throws Exception {
		Path traceDir = temp.resolve("traces");
		var response = executeLegacy("session-l", traceDir);
		assertThat(response.getResult().getOutput()).isEqualTo("sentinel-answer");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("SUCCESS");
		assertThat(response.getMetadata().<Object>get("phaseCapture"))
			.isInstanceOf(io.github.markpollack.journal.claude.PhaseCapture.class);
		assertThat(response.getMetadata().<Object>get("inputTokens")).isEqualTo(1);
		assertThat(response.getMetadata().<Object>get("outputTokens")).isEqualTo(1);
		assertThat(ClaudeAcquisition.from(response)).isNull();
		String tracePath = response.getMetadata().get("tracePath");
		assertThat(tracePath).isNotNull();
		Path trace = Path.of(tracePath);
		assertThat(trace).exists();
		assertThat(trace.getParent()).isEqualTo(traceDir.toAbsolutePath().normalize());
		assertThat(trace.getFileName().toString()).startsWith("agent-run-").endsWith(".jsonl");
		var lines = Files.readAllLines(trace);
		assertThat(lines.get(0)).contains("\"type\":\"header\"").contains("\"schemaVersion\":2");
		assertThat(lines.getLast()).contains("\"type\":\"result\"").contains("\"sessionId\":\"session-l\"");
		try (var entries = Files.list(traceDir)) {
			assertThat(entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()))
				.allMatch("transcripts"::equals);
		}
	}

	@Test
	void knownSessionWithoutItsTranscriptIsNeverGivenAnotherRunsTranscript() throws Exception {
		Path projects = temp.resolve("projects");
		Path cwd = temp.resolve("work");
		String slug = cwd.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
		Path other = projects.resolve(slug).resolve("other-session.jsonl");
		Files.createDirectories(other.getParent());
		Files.writeString(other, "{\"sessionId\":\"other-session\"}\n");
		assertThat(ClaudeAgentModel.findTranscript("known-session", projects, cwd)).as("emitted identity has no match")
			.isNull();
		assertThat(ClaudeAgentModel.findTranscript("other-session", projects, cwd)).isEqualTo(other);
		assertThat(ClaudeAgentModel.findTranscript(null, projects, cwd)).as("failed run: slug fallback")
			.isEqualTo(other);
	}

	@Test
	void legacyTraceDirMayDisableTranscriptArchiveButExplicitAcquisitionMayNot() {
		assertThatCode(() -> ClaudeAgentModel.builder().traceDir(temp).archiveTranscript(false).build().close())
			.doesNotThrowAnyException();
		var capture = new ClaudeCaptureOptions(temp.resolve("package"), "invocation", "2.1.316");
		assertThatThrownBy(() -> ClaudeAgentModel.builder().capture(capture).archiveTranscript(false).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	private io.github.markpollack.agents.model.AgentResponse executeLegacy(String session, Path traceDir)
			throws Exception {
		try (var model = ClaudeAgentModel.builder()
			.traceDir(traceDir)
			.claudePath(fakeClaude(session, false).toString())
			.timeout(Duration.ofSeconds(5))
			.defaultOptions(ClaudeAgentOptions.builder().model("test-model").yolo(true).build())
			.build()) {
			return model.call(new AgentTaskRequest("find sentinel", temp, null));
		}
	}

	private Path fakeClaude(String session, boolean error) throws Exception {
		Path executable = temp.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/bin/sh
						echo '{"type":"system","subtype":"init","session_id":"%s"}'
						IFS= read -r line
						echo '{"type":"unknown_event","value":"preserved"}'
						printf 'stderr-tail' >&2
						echo '{"type":"result","subtype":"success","is_error":%s,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"sentinel-answer","session_id":"%s","usage":{"input_tokens":1,"output_tokens":1}}'
						"""
					.formatted(session, error, session));
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		return executable;
	}

	private Path nativeTranscript(String filename, String sessionId) throws Exception {
		Path nativeDir = temp.resolve("native");
		Files.createDirectories(nativeDir.resolve("project"));
		Files.writeString(nativeDir.resolve("project/" + filename + ".jsonl"), "{\"type\":\"user\",\"sessionId\":\""
				+ sessionId + "\",\"message\":{\"content\":\"find sentinel\"}}\n");
		return nativeDir;
	}

	private io.github.markpollack.agents.model.AgentResponse execute(String session, boolean error, Path nativeDir,
			Path output) throws Exception {
		Path executable = fakeClaude(session, error);
		var capture = new ClaudeCaptureOptions(output, "invocation-a", "2.1.316", nativeDir,
				Map.of("fixture", "synthetic"));
		try (var model = ClaudeAgentModel.builder()
			.capture(capture)
			.claudePath(executable.toString())
			.timeout(Duration.ofSeconds(5))
			.defaultOptions(ClaudeAgentOptions.builder().model("test-model").yolo(true).build())
			.build()) {
			return model.call(new AgentTaskRequest("find sentinel", temp, null));
		}
	}

}
