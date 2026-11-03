/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.client.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(25)
class RetainedGenerationTest {

	@TempDir
	Path root;

	@Test
	void actualBoundaryPrefixesAndOriginalDecisionsProduceSharedGenerationClaims() throws Exception {
		var value = run(true);
		var capture = value.capture();
		assertThatThrownBy(() -> capture.writeGeneration(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> capture.writeGeneration(List.of(value.decisions().getFirst(), value.decisions().getFirst())))
			.isInstanceOf(IllegalArgumentException.class);
		Path manifest = capture.writeGeneration(value.decisions());
		var generation = CaptureFiles.JSON.readTree(manifest.toFile());
		assertThat(generation.path("kind").asText()).isEqualTo("journal-retained-generation/1");
		assertThat(generation.path("logicalSessionId").asText()).isEqualTo(value.decisions().getFirst().sessionId());
		var first = generation.path("turns").get(0);
		var second = generation.path("turns").get(1);
		assertThat(first.path("nativePrefixBefore").path("bytes").asLong()).isZero();
		assertThat(first.path("nativePrefixAfter").path("bytes").asLong()).isEqualTo(value.firstBytes().length);
		assertThat(first.path("nativePrefixAfter").path("sha256").asText())
			.isEqualTo(NativePrefixObservation.hash(value.firstBytes()));
		assertThat(second.path("nativePrefixBefore")).isEqualTo(first.path("nativePrefixAfter"));
		assertThat(second.path("nativePrefixAfter").path("bytes").asLong()).isGreaterThan(value.firstBytes().length);
		assertThat(first.path("nativeRecords")).hasSize(2);
		assertThat(second.path("nativeRecords")).hasSize(2);
		assertThat(generation.path("sources")).hasSize(4);
		assertThat(generation.path("shared")).hasSize(1);
		assertThat(generation.path("unassigned")).isEmpty();
		assertThatThrownBy(() -> capture.writeGeneration(value.decisions()))
			.isInstanceOf(FileAlreadyExistsException.class);
	}

	@Test
	void absentInitialFileRemainsUnknownAndCannotEarnCompleteProfile() throws Exception {
		var value = run(false);
		assertThat(value.decisions()).allMatch(d -> d.outcome() instanceof ExecutionOutcome.Success);
		assertThatThrownBy(() -> value.capture().writeGeneration(value.decisions()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("boundary prefix unavailable");
	}

	@Test
	void alteredOriginalCannotBeRehashedIntoNewGenerationClaim() throws Exception {
		var value = run(true);
		Path diagnostic = value.capture().write();
		Files.writeString(diagnostic.getParent().resolve("stdout.bin"), "changed");
		assertThatThrownBy(() -> value.capture().writeGeneration(value.decisions()))
			.isInstanceOf(java.io.IOException.class)
			.hasMessageContaining("source changed");
		Files.writeString(diagnostic, "{}");
		assertThatThrownBy(() -> value.capture().writeGeneration(value.decisions()))
			.isInstanceOf(java.io.IOException.class)
			.hasMessageContaining("declaration changed");
	}

	@Test
	void nativePrefixObservationIsBoundedAndReportsUnavailableFiles() throws Exception {
		assertThat(NativePrefixObservation.observe(null)).containsKey("unavailable");
		assertThat(NativePrefixObservation.observe(root.resolve("absent"))).containsKey("unavailable");
		assertThat(NativePrefixObservation.observe(root)).containsKey("unavailable");
		Path large = root.resolve("large");
		Files.write(large, new byte[4 * 1024 * 1024 + 1]);
		assertThat(NativePrefixObservation.observe(large)).containsKey("unavailable");
		Path original = Files.writeString(root.resolve("N.jsonl"), "original");
		Files.createDirectory(root.resolve("N"));
		Files.writeString(root.resolve("N/sidecar"), "owned");
		assertThatThrownBy(() -> NativePrefixObservation.copy(original, root.resolve("copy"), "N"))
			.isInstanceOf(java.io.IOException.class)
			.hasMessageContaining("child/sidecar");
	}

	@Test
	void framingPreservesUnknownAndPartialRecords() {
		var records = RetainedRecord.read("STDERR",
				"bad\nnull\n{}\npartial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(records).hasSize(4).allMatch(r -> !r.shared());
		assertThat(records.getLast().locator()).containsEntry("end", 19);
		var controls = RetainedRecord.read("STDIN",
				"{\"type\":\"control_response\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(controls.getFirst().shared()).isTrue();
	}

	private Run run(boolean createInitial) throws Exception {
		Path projects = Files.createDirectories(root.resolve("native-projects/project"));
		Path transcript = projects.resolve("fixture-history.jsonl");
		if (createInitial)
			Files.createFile(transcript);
		Path child = root.resolve("child.py");
		try (var stream = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(stream, child);
		}
		Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		var captures = new ArrayBlockingQueue<ClaudeRetainedCapture>(2);
		ExecutionRecorder recorder = new ExecutionRecorder() {
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> evidence) {
				captures.add((ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
				return () -> d.decisionId();
			}
		};
		var provider = ClaudeClientProvider.builder()
			.claudePath(child.toString())
			.workingDirectory(root)
			.acquisitionDirectory(root.resolve("captures"), "2.1.316")
			.retainedTranscript(transcript)
			.build();
		var decisions = new ArrayList<ExecutionDecision>();
		byte[] firstBytes;
		try (var client = AgentClient.builder(provider)
			.defaultTimeout(Duration.ofSeconds(5))
			.executionRecorder(recorder)
			.build(); var session = client.sessions().create(s -> {
			})) {
			var first = session.goal("first").runAsync();
			first.result();
			decisions.add(first.decision().orElseThrow());
			firstBytes = Files.readAllBytes(transcript);
			var second = session.goal("second").runAsync();
			second.result();
			decisions.add(second.decision().orElseThrow());
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (captures.size() != 2 && System.nanoTime() < until)
				Thread.sleep(5);
			assertThat(captures).hasSize(2);
		}
		return new Run(captures.remove(), decisions, firstBytes);
	}

	private record Run(ClaudeRetainedCapture capture, List<ExecutionDecision> decisions, byte[] firstBytes) {
	}

}
