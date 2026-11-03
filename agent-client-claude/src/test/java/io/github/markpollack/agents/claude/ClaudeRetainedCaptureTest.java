/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver.Stream;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class ClaudeRetainedCaptureTest {

	@TempDir
	Path root;

	@Test
	void twoRealTurnsRemainUsableWhileRecordingWaitsAndExportOnlyAfterClose() throws Exception {
		Path child = root.resolve("child.py");
		try (var input = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(input, child);
		}
		Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		var captures = new ConcurrentHashMap<String, ClaudeRetainedCapture>();
		var release = new CountDownLatch(1);
		ExecutionRecorder recorder = new ExecutionRecorder() {
			public java.util.function.Supplier<String> prepare(ExecutionDecision decision) {
				throw new AssertionError();
			}

			public java.util.function.Supplier<String> prepare(ExecutionDecision decision,
					Map<String, Object> evidence) {
				captures.put(decision.executionId(), (ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
				return () -> {
					try {
						if (!release.await(8, TimeUnit.SECONDS))
							throw new AssertionError("recording latch");
					}
					catch (InterruptedException ex) {
						throw new AssertionError(ex);
					}
					return decision.decisionId();
				};
			}
		};
		var provider = ClaudeClientProvider.builder()
			.workingDirectory(root)
			.claudePath(child.toString())
			.acquisitionDirectory(root.resolve("captures"), "fixture")
			.build();
		ClaudeRetainedCapture capture;
		try (var client = AgentClient.builder(provider).executionRecorder(recorder).build()) {
			try (var session = client.sessions().create(s -> {
			})) {
				var first = session.goal("first").runAsync();
				assertThat(first.result(Duration.ofSeconds(5)).getResult()).isEqualTo("first");
				await(() -> captures.containsKey(first.executionId()));
				capture = captures.get(first.executionId());
				assertThat(capture.sealed()).isFalse();
				assertThatThrownBy(capture::write).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("not EOF");
				var original = first.decision().orElseThrow();
				var second = session.goal("second").runAsync();
				assertThat(second.result(Duration.ofSeconds(5)).getResult()).isEqualTo("second");
				assertThat(second.decision().orElseThrow().decisionId()).isNotEqualTo(original.decisionId());
				assertThat(second.decision().orElseThrow().connectionId()).isEqualTo(original.connectionId());
				assertThat(first.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.PENDING);
				assertThat(first.recording().delivery()).isNotInstanceOf(TerminalDelivery.Completed.class);
				release.countDown();
				await(() -> captures.containsKey(second.executionId()));
				await(() -> first.recording().delivery() instanceof TerminalDelivery.Completed
						&& second.recording().delivery() instanceof TerminalDelivery.Completed);
				assertThat(captures.get(second.executionId())).isSameAs(capture);
				assertThat(first.decision().orElseThrow()).isSameAs(original);
			}
			assertThat(capture.sealed()).isTrue();
			Path manifest = capture.write(root.resolve("absent-projects"));
			assertThat(capture.write()).isEqualTo(manifest);
			var value = CaptureFiles.JSON.readTree(manifest.toFile());
			assertThat(value.path("transportComplete").asBoolean()).isTrue();
			assertThat(value.path("archiveQualified").asBoolean()).isFalse();
			assertThat(value.path("turns")).hasSize(2);
			for (var turn : value.path("turns")) {
				assertThat(turn.path("promptCandidates")).hasSize(1);
				assertThat(turn.path("resultCandidates")).hasSize(1);
			}
			assertThat(Files.readAllBytes(manifest.getParent().resolve("stdin.bin")))
				.isEqualTo(Files.readAllBytes(root.resolve("wire.jsonl")));
			assertThat(Files.readAllLines(root.resolve("launches.jsonl"))).hasSize(1);
			assertThat(Files.readAllLines(root.resolve("prompts.jsonl"))).hasSize(2);
			try (var files = Files.list(root)) {
				for (var file : files.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
					assertThat(ProcessHandle.of(Long.parseLong(Files.readString(file)))
						.map(ProcessHandle::isAlive)
						.orElse(false)).isFalse();
			}
		}
		finally {
			release.countDown();
		}
	}

	@Test
	void overflowPreservesOnlyOriginalPrefixAndCannotClaimComplete() throws Exception {
		var capture = capture();
		byte[] prefix = "original".getBytes(StandardCharsets.UTF_8);
		capture.observe(Stream.STDOUT, prefix);
		prefix[0] = 'X';
		capture.observe(Stream.STDOUT, new byte[4 * 1024 * 1024]);
		capture.observe(Stream.STDOUT, "tail".getBytes(StandardCharsets.UTF_8));
		capture.seal(true, true, true);
		var node = CaptureFiles.JSON.readTree(capture.write(root.resolve("missing")).toFile());
		assertThat(node.path("overflow").asBoolean()).isTrue();
		assertThat(node.path("transportComplete").asBoolean()).isFalse();
		assertThat(Files.readString(root.resolve("G/stdout.bin"))).isEqualTo("original");
		var source = node.path("sources").get(1);
		assertThat(source.path("dropped").asLong()).isEqualTo(4 * 1024 * 1024 + 4);
	}

	@Test
	void metadataIsBoundedAndOpenOrUnterminatedStreamsCannotClaimComplete() throws Exception {
		var capture = capture();
		for (int i = 0; i < 16385; i++)
			capture.observe(Stream.STDERR, new byte[0]);
		for (int i = 0; i < 257; i++)
			capture.begin("E" + i);
		capture.seal(false, false, false);
		capture.seal(true, true, true);
		capture.observe(Stream.STDOUT, new byte[] { 1 });
		var node = CaptureFiles.JSON.readTree(capture.write(root.resolve("missing")).toFile());
		assertThat(node.path("observations")).hasSize(16384);
		assertThat(node.path("turns")).hasSize(256);
		assertThat(node.path("stdoutNaturalEof").asBoolean()).isFalse();
		assertThat(node.path("transportComplete").asBoolean()).isFalse();
	}

	@Test
	void locatorsRetainOriginalFramingWithoutClaimingAmbiguousOrReadAheadMembership() throws Exception {
		var capture = capture();
		capture.observe(Stream.STDOUT, "{\"type\":\"result\"}\n".getBytes(StandardCharsets.UTF_8)); // read
																									// ahead
		capture.begin("E");
		capture.observe(Stream.STDIN, "{\"type\":\"user\"}\n".getBytes(StandardCharsets.UTF_8));
		capture.observe(Stream.STDOUT, "bad\nnull\n{}\n{\"type\":\"res".getBytes(StandardCharsets.UTF_8));
		capture.observe(Stream.STDOUT, "ult\"}\n{\"type\":\"result\"}\ntail".getBytes(StandardCharsets.UTF_8));
		capture.end("E", null);
		capture.end("unknown", null);
		capture.seal(true, true, true);
		var node = CaptureFiles.JSON.readTree(capture.write(root.resolve("missing")).toFile());
		assertThat(node.path("turns").get(0).path("resultCandidates")).hasSize(2);
		assertThat(node.path("archiveQualified").asBoolean()).isFalse();
		assertThat(node.path("membership").asText()).contains("unassigned");
	}

	@Test
	void nativeFinalSnapshotRetainsExactBytesButNeverClaimsTurnMembership() throws Exception {
		Path projects = Files.createDirectories(root.resolve("projects/project"));
		Path nativeFile = projects.resolve("history.jsonl");
		String bytes = "{\"sessionId\":\"history\",\"type\":\"user\",\"message\":{\"content\":\"first\"}}\n";
		Files.writeString(nativeFile, bytes);
		var capture = capture();
		capture.begin("E");
		capture.end("E", "history");
		capture.seal(true, true, true);
		var node = CaptureFiles.JSON.readTree(capture.write(projects.getParent()).toFile());
		assertThat(Files.readString(root.resolve("G/native/transcript.jsonl"))).isEqualTo(bytes);
		assertThat(node.path("nativeFiles").get(0).path("end").asText()).isEqualTo("snapshot_extent");
		assertThat(node.path("nativeAttribution").asText()).contains("not qualified");
	}

	@Test
	void wrongNativeIdentityIsReportedAndExistingExportNeverOverwritten() throws Exception {
		Path projects = Files.createDirectories(root.resolve("projects/project"));
		Files.writeString(projects.resolve("history.jsonl"), "{\"sessionId\":\"wrong\"}\n");
		var capture = capture();
		capture.end("E", "history");
		capture.seal(true, true, false);
		var node = CaptureFiles.JSON.readTree(capture.write(projects.getParent()).toFile());
		assertThat(node.path("nativeCaptureError").asText()).contains("mismatch");
		var collision = capture();
		collision.seal(true, true, true);
		assertThatThrownBy(() -> collision.write(projects)).isInstanceOf(FileAlreadyExistsException.class);
	}

	private ClaudeRetainedCapture capture() {
		return new ClaudeRetainedCapture("G", root, "fixture");
	}

	private static void await(java.util.function.BooleanSupplier condition) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < until)
			Thread.sleep(5);
		assertThat(condition.getAsBoolean()).isTrue();
	}

}
