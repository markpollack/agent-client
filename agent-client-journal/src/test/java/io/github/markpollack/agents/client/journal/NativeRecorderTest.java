/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.ClaudeAcquisition;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Synthetic source bytes plus real Client decision/qualifier/receiver; native proof is
 * separate.
 */
class NativeRecorderTest {

	@TempDir
	Path root;

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void acceptedOneShotDecisionAndNativeSourcesReachActualReader() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			var decision = execution.decision().orElseThrow();
			var source = source(decision);
			var store = root.resolve("store");
			var recorder = JournalExecutionRecorder.claude(store, "native-client");
			var prepared = recorder.prepare(decision, Map.of("claudeAcquisition", source));
			String ack = prepared.get();
			assertThat(prepared.get()).isEqualTo(ack);
			Path events;
			try (var files = Files.walk(store.resolve("prepared"))) {
				events = files.filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
			}
			var pkg = PackageReader.open(events);
			assertThat(pkg.diagnostics).noneMatch(d -> Set.of("DEFECT", "REFUSED").contains(d.severity().name()));
			assertThat(TerminalBindingReader.read(pkg.declarations).decision())
				.isEqualTo(DecisionEncoding.decision(decision));
			assertThat(Files.readString(events)).contains("client-terminal-claude-2.1.316/1", "native-1");
			assertThat(source.manifest()).exists();
			var partial = new ClaudeAcquisition(source.manifest(), source.sha256(), false, source.invocationId(),
					source.sessionId());
			assertThatThrownBy(() -> JournalExecutionRecorder.claude(root.resolve("partial"), "p")
				.prepare(decision, Map.of("claudeAcquisition", partial)))
				.isInstanceOf(RecordingDeliveryException.class);
			Files.writeString(source.manifest(), "changed");
			assertThatThrownBy(() -> JournalExecutionRecorder.claude(root.resolve("changed"), "c")
				.prepare(decision, Map.of("claudeAcquisition", source))).isInstanceOf(RecordingDeliveryException.class);
		}
	}

	@Test
	void typedIntegrityDoesNotRetryOrRewriteAcceptedOutcome() throws Exception {
		var receiver = new TerminalReceiver(root, "integrity", WriterOptions.defaults().withFaults((op, detail) -> {
			if (op.equals("terminal-reply"))
				throw new TerminalIntegrityException("verified corruption");
		}));
		var recorder = new JournalExecutionRecorder(receiver, root, "integrity");
		try (var client = AgentClient.builder(JournalExecutionRecorderTest.provider("SUCCESS"))
			.executionRecorder(recorder)
			.build()) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			JournalExecutionRecorderTest.awaitRecording(execution);
			var failure = (TerminalDelivery.Failed) execution.recording().delivery();
			assertThat(failure.retryable()).isFalse();
			assertThat(failure.acceptanceUnknown()).isTrue();
			assertThat(failure.failure().code()).isEqualTo(FailureCode.RECORDING_FAILED);
			assertThat(execution.result().isSuccessful()).isTrue();
		}
	}

	private ClaudeAcquisition source(ExecutionDecision decision) throws Exception {
		Path raw = Files.createDirectory(root.resolve("raw"));
		Files.writeString(raw.resolve("stdin.bin"),
				"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
		Files.writeString(raw.resolve("stdout.bin"), """
				{"type":"system","subtype":"init","session_id":"native-1","claude_code_version":"2.1.316"}
				{"type":"result","subtype":"success","session_id":"native-1","result":"hello","is_error":false}
				""");
		Files.writeString(raw.resolve("stderr.bin"), "");
		Files.writeString(raw.resolve("native.jsonl"),
				"""
						{"type":"user","uuid":"u1","sessionId":"native-1","version":"2.1.316","message":{"role":"user","content":"hello"}}
						""");
		var manifest = json.readTree(
				"""
						{"kind":"agent-journal/acquired-execution","version":"0.1","profile":"claude-code-2.1.316-sdk-stdio-session-file",
						"profile_id":"claude-code/2.1.316/sdk-stdio+session-file","complete":true,"capture_errors":[],
						"run":{"invocation_index":0,"session_uid":"native-1","capture_start":"2026-11-02T00:00:00Z",
						"cli":{"name":"Claude Code","version":"2.1.316","vendor_name":"Anthropic"},"launch_settings":{"route":"sdk_stdio"}},
						"process":{"argv":["claude","--input-format","stream-json","--output-format","stream-json"],"exit_code":null},
						"turns":[{"start_offsets":{"STDIN":0,"STDOUT":0,"STDERR":0},"end_offsets":{}}],"sources":[]}
						""");
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("acquisition_uid", decision.executionId());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("run")).put("run_uid", decision.executionId());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("process")).put("connection_uid",
				decision.connectionId().orElseThrow());
		var turn = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("turns").get(0);
		turn.put("invocation_uid", decision.executionId());
		var sources = (com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("sources");
		for (String role : List.of("stdin", "stream", "stderr", "transcript")) {
			String path = switch (role) {
				case "stream" -> "stdout.bin";
				case "transcript" -> "native.jsonl";
				default -> role + ".bin";
			};
			byte[] bytes = Files.readAllBytes(raw.resolve(path));
			var source = sources.addObject()
				.put("artifact", path)
				.put("path", path)
				.put("role", role)
				.put("origin", "observed/" + path)
				.put("state", "acquired")
				.put("acquisition", decision.executionId())
				.put("required", true)
				.put("end", "EOF")
				.put("bytes", bytes.length)
				.put("sha256", hash(bytes));
			if (role.equals("stream") || role.equals("transcript"))
				source.put("profile", role.equals("stream") ? "claude_stream" : "claude_transcript")
					.put("route", role.equals("stream") ? "sdk_stdio" : "native_session_file")
					.put("framing", "lines")
					.put("records", role.equals("stream") ? 2 : 1)
					.put("owner_session_uid", "native-1");
			else
				source.put("archive_only", true);
			if (!role.equals("transcript"))
				((com.fasterxml.jackson.databind.node.ObjectNode) turn.get("end_offsets"))
					.put(role.equals("stream") ? "STDOUT" : role.toUpperCase(Locale.ROOT), bytes.length);
		}
		Path file = raw.resolve("sealed.json");
		json.writeValue(file.toFile(), manifest);
		return new ClaudeAcquisition(file, hash(Files.readAllBytes(file)), true, decision.executionId(), "native-1");
	}

	private static String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
