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

	static final String CLAUDE = "claude-code-2.1.316-sdk-stdio-session-file";

	@Test
	void acceptedOneShotDecisionAndNativeSourcesReachActualReader() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			var decision = execution.decision().orElseThrow();
			var source = source(decision);
			byte[] sealedBytes = Files.readAllBytes(source.manifest());
			var store = root.resolve("store");
			var recorder = JournalExecutionRecorder.claude(store, "native-client");
			var prepared = recorder.prepareRecording(decision, Optional.of(generation(decision, source, true)));
			assertThat(prepared.nativeRecording().outcome()).isEqualTo(NativeRecording.Outcome.ATTACHED);
			assertThat(prepared.nativeRecording().terminalProfile()).isEqualTo(TerminalReceiver.CLAUDE_PROFILE);
			String ack = prepared.delivery().get();
			assertThat(prepared.delivery().get()).isEqualTo(ack);
			Path events = events(store);
			var pkg = PackageReader.open(events);
			assertThat(pkg.diagnostics).noneMatch(d -> Set.of("DEFECT", "REFUSED").contains(d.severity().name()));
			assertThat(TerminalBindingReader.read(pkg.declarations).decision())
				.isEqualTo(DecisionEncoding.decision(decision));
			assertThat(Files.readString(events)).contains("client-terminal-claude-2.1.316/1", "native-1");
			// Review F04: the sealed manifest is never rewritten.
			assertThat(Files.readAllBytes(source.manifest())).isEqualTo(sealedBytes);
		}
	}

	@Test
	void incompleteOrChangedSourcesAreRejectedButStillArchiveTheDecision() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			var decision = execution.decision().orElseThrow();
			var source = source(decision);
			var partial = JournalExecutionRecorder.claude(root.resolve("partial"), "p")
				.prepareRecording(decision, Optional.of(generation(decision, source, false)));
			assertRejected(partial, root.resolve("partial"), decision, "Native acquisition is incomplete");
			Files.writeString(source.manifest(), "changed");
			var changed = new JournalExecutionRecorder(root.resolve("changed"), "c").prepareRecording(decision,
					Optional.of(generation(decision, source, true)));
			assertRejected(changed, root.resolve("changed"), decision, "Acquisition manifest changed after sealing");
		}
	}

	@Test
	void qualifierVerdictIsRejectedNotUnqualified() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			var decision = execution.decision().orElseThrow();
			Path manifest = Files.writeString(root.resolve("bad.json"), "{\"profile\":\"" + CLAUDE + "\"}");
			var bad = NativeGeneration.oneShot(CLAUDE, decision, manifest, NativeGeneration.sha256(manifest), true, "");
			var prepared = new JournalExecutionRecorder(root.resolve("bad"), "b").prepareRecording(decision,
					Optional.of(bad));
			assertRejected(prepared, root.resolve("bad"), decision, "qualifier refused");
		}
	}

	@Test
	void unknownProfileIsUnqualifiedWithTheProfileInMissing() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var execution = client.goal("hello").runAsync();
			execution.result();
			var decision = execution.decision().orElseThrow();
			Path manifest = Files.writeString(root.resolve("other.json"), "{}");
			var other = NativeGeneration.oneShot("antigravity-acquisition-v0.1", decision, manifest,
					NativeGeneration.sha256(manifest), true, "");
			var store = root.resolve("unqualified");
			var prepared = new JournalExecutionRecorder(store, "u").prepareRecording(decision, Optional.of(other));
			assertThat(prepared.nativeRecording().outcome()).isEqualTo(NativeRecording.Outcome.UNQUALIFIED);
			assertThat(prepared.nativeRecording().detail()).contains("antigravity-acquisition-v0.1");
			assertThat(prepared.delivery().get()).isNotBlank();
			var association = json
				.readTree(TerminalBindingReader.read(PackageReader.open(events(store)).declarations).association());
			assertThat(association.path("acquisition").asText()).isEqualTo("UNAVAILABLE");
			assertThat(association.path("sources")).isEmpty();
			assertThat(association.path("missing").get(0).path("reason").asText())
				.isEqualTo("UNQUALIFIED: No Journal qualifier for acquisition profile antigravity-acquisition-v0.1");
			assertThat(Files.readString(events(store))).doesNotContain("input_tokens");
		}
	}

	@Test
	void foreignGenerationIsRejected() throws Exception {
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var first = client.goal("one").runAsync();
			first.result();
			var second = client.goal("two").runAsync();
			second.result();
			var source = source(first.decision().orElseThrow());
			var decision = second.decision().orElseThrow();
			var prepared = new JournalExecutionRecorder(root.resolve("foreign"), "f").prepareRecording(decision,
					Optional.of(generation(first.decision().orElseThrow(), source, true)));
			assertRejected(prepared, root.resolve("foreign"), decision, "does not belong");
		}
	}

	@Test
	void outcomeIsVisibleThroughRecordingAndNeverGatesTheResult() throws Exception {
		var store = root.resolve("visible");
		var recorder = new JournalExecutionRecorder(store, "visible");
		AgentExecutionProvider provider = options -> new AgentExecutionProvider.Connection() {
			@Override
			public AgentExecutionProvider.Turn prepare(String id) {
				return new AgentExecutionProvider.Turn() {
					@Override
					public io.github.markpollack.agents.model.AgentResponse call(
							io.github.markpollack.agents.model.AgentTaskRequest request) {
						return JournalExecutionRecorderTest.provider("SUCCESS").open(options).prepare(id).call(request);
					}

					@Override
					public Optional<NativeGeneration> generation(ExecutionDecision decision) {
						throw new IllegalStateException("sealing broke");
					}

					@Override
					public void stop(boolean interrupt) {
					}
				};
			}

			@Override
			public void close() {
			}
		};
		try (var client = AgentClient.builder(provider).executionRecorder(recorder).build()) {
			var execution = client.goal("hello").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			JournalExecutionRecorderTest.awaitRecording(execution);
			assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
			var observed = execution.recording().nativeRecording().orElseThrow();
			assertThat(observed.outcome()).isEqualTo(NativeRecording.Outcome.NOT_CAPTURED);
			assertThat(observed.detail()).isEqualTo("Native sealing failed: sealing broke");
			assertThat(execution.result().isSuccessful()).isTrue();
		}
	}

	private void assertRejected(ExecutionRecorder.Prepared prepared, Path store, ExecutionDecision decision,
			String verdict) throws Exception {
		assertThat(prepared.nativeRecording().outcome()).isEqualTo(NativeRecording.Outcome.REJECTED);
		assertThat(prepared.nativeRecording().detail()).contains(verdict);
		assertThat(prepared.nativeRecording().terminalProfile()).isEqualTo(TerminalReceiver.PROFILE);
		assertThat(prepared.delivery().get()).isNotBlank();
		var binding = TerminalBindingReader.read(PackageReader.open(events(store)).declarations);
		assertThat(binding.decision()).isEqualTo(DecisionEncoding.decision(decision));
		var reason = json.readTree(binding.association()).path("missing").get(0).path("reason").asText();
		assertThat(reason).startsWith("REJECTED: ").contains(verdict);
	}

	private static NativeGeneration generation(ExecutionDecision decision, ClaudeAcquisition source, boolean complete) {
		return NativeGeneration.oneShot(CLAUDE, decision, source.manifest(), source.sha256(), complete, "");
	}

	private static Path events(Path store) throws Exception {
		try (var files = Files.walk(store.resolve("prepared"))) {
			return files.filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
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
		Path raw = Files.createDirectory(root.resolve("raw-" + decision.executionId()));
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
