/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.*;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Actual SDK child, observed native boundaries, original decisions and owning Journal.
 */
@Timeout(30)
class RetainedRecorderTest {

	@TempDir
	Path root;

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void twoTurnsProgressWhileInitialDeliveryIsBlockedThenAttachToOriginalDecisions() throws Exception {
		Path store = root.resolve("store");
		var recorder = JournalExecutionRecorder.retainedClaude(store, "retained-test");
		var run = run(recorder);
		assertThatThrownBy(() -> recorder.attachment("absent")).isInstanceOf(IllegalArgumentException.class);
		List<Path> initial = events(store);
		assertThat(initial).hasSize(2);
		for (var e : run.executions()) {
			var original = e.decision().orElseThrow();
			String initialAck = ((TerminalDelivery.Completed) e.recording().delivery()).bindingId();
			assertThat(recorder.attachment(e.executionId()).initialBindingId()).contains(initialAck);
			assertThat(recorder.attachment(e.executionId()).delivery()).isInstanceOf(TerminalDelivery.Pending.class);
			String finalAck = recorder.attach(e.executionId(), run.manifest());
			assertThat(finalAck).isNotEqualTo(initialAck);
			assertThat(recorder.attach(e.executionId(), run.manifest())).isEqualTo(finalAck);
			assertThat(recorder.prepare(original).get()).isEqualTo(initialAck);
			assertThat(e.decision().orElseThrow()).isSameAs(original);
			assertThat(e.recording().delivery()).isEqualTo(new TerminalDelivery.Completed(initialAck,
					((TerminalDelivery.Completed) e.recording().delivery()).acknowledgedAt(), false));
			assertThat(recorder.attachment(e.executionId()).decisionId()).isEqualTo(original.decisionId());
			assertThat(recorder.attachment(e.executionId()).delivery()).isInstanceOf(TerminalDelivery.Completed.class);
			assertThatThrownBy(() -> recorder.attach(e.executionId(), root.resolve("other.json")))
				.isInstanceOf(IllegalArgumentException.class);
		}
		List<Path> all = events(store);
		assertThat(all).hasSize(4);
		for (Path event : all) {
			var pkg = PackageReader.open(event);
			assertThat(pkg.diagnostics).noneMatch(d -> Set.of("DEFECT", "REFUSED").contains(d.severity().name()));
			String decision = TerminalBindingReader.read(pkg.declarations).decision();
			assertThat(run.executions().stream().map(e -> DecisionEncoding.decision(e.decision().orElseThrow())))
				.contains(decision);
		}
		for (var e : run.executions()) {
			Path qualified = store.resolve("retained-" + e.executionId());
			Path finalEvent = all.stream().filter(p -> !initial.contains(p)).filter(p -> {
				try {
					return TerminalBindingReader.read(PackageReader.open(p).declarations)
						.decision()
						.equals(DecisionEncoding.decision(e.decision().orElseThrow()));
				}
				catch (Exception ex) {
					throw new AssertionError(ex);
				}
			}).findFirst().orElseThrow();
			assertThat(
					OcsfReaderTool.run(
							new String[] { "verify", finalEvent.toString(), qualified.toString(), "--acquisition",
									qualified.resolve("acquisition.json").toString(), "--assembly",
									qualified.resolve("assembly-expectation.json").toString() },
							System.out, System.err))
				.isZero();
		}
		try (var client = AgentClient.create(JournalExecutionRecorderTest.provider("SUCCESS"))) {
			var third = client.goal("third").runAsync();
			third.result();
			assertThatThrownBy(() -> recorder.prepare(third.decision().orElseThrow()))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void lostFinalReplyRemainsUncertainAndExplicitRetryReusesFrozenAssociation() throws Exception {
		var lose = new AtomicBoolean();
		Path store = root.resolve("store");
		var receiver = new TerminalReceiver(store, "retained-test",
				WriterOptions.defaults().withFaults((op, detail) -> {
					if (op.equals("terminal-reply") && lose.getAndSet(false))
						throw new IOException("lost final reply");
				}));
		var recorder = new RetainedJournalRecorder(receiver, store, "retained-test");
		var run = run(recorder);
		var first = run.executions().getFirst();
		lose.set(true);
		assertThatThrownBy(() -> recorder.attach(first.executionId(), run.manifest()))
			.isInstanceOf(RecordingDeliveryException.class);
		var failed = (TerminalDelivery.Failed) recorder.attachment(first.executionId()).delivery();
		assertThat(failed.failure().code()).isEqualTo(FailureCode.PERSISTENCE_FAILED);
		assertThat(failed.acceptanceUnknown()).isTrue();
		assertThat(failed.retryable()).isFalse();
		int before = events(store).size();
		// Retry is the previously frozen association; it does not reinterpret mutable
		// input.
		Files.writeString(run.manifest(), "changed after prepared delivery");
		String ack = recorder.attach(first.executionId(), run.manifest());
		assertThat(recorder.attach(first.executionId(), run.manifest())).isEqualTo(ack);
		assertThat(events(store)).hasSize(before);
		assertThat(first.result().isSuccessful()).isTrue();
	}

	@Test
	void preparationFailureIsObservableAndDoesNotChangeOriginalSuccess() throws Exception {
		var recorder = JournalExecutionRecorder.retainedClaude(root.resolve("store"), "retained-test");
		var run = run(recorder);
		var first = run.executions().getFirst();
		var generation = (ObjectNode) json.readTree(run.manifest().toFile());
		generation.put("logicalSessionId", "wrong-L");
		json.writeValue(run.manifest().toFile(), generation);
		assertThatThrownBy(() -> recorder.attach(first.executionId(), run.manifest()))
			.isInstanceOf(RecordingDeliveryException.class);
		var failure = (TerminalDelivery.Failed) recorder.attachment(first.executionId()).delivery();
		assertThat(failure.acceptanceUnknown()).isFalse();
		assertThat(failure.failure().code()).isEqualTo(FailureCode.RECORDING_FAILED);
		generation.put("logicalSessionId", first.decision().orElseThrow().sessionId()).put("connectionId", "wrong-G");
		json.writeValue(run.manifest().toFile(), generation);
		assertThatThrownBy(() -> recorder.attach(first.executionId(), run.manifest()))
			.isInstanceOf(RecordingDeliveryException.class);
		assertThatThrownBy(() -> recorder.attach(first.executionId(), root.resolve("missing")))
			.isInstanceOf(RecordingDeliveryException.class);
		assertThat(first.result().isSuccessful()).isTrue();
		assertThat(first.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
	}

	private Run run(RetainedJournalRecorder journal) throws Exception {
		Path projects = Files.createDirectories(root.resolve("native-projects/project"));
		Path transcript = Files.createFile(projects.resolve("fixture-history.jsonl"));
		Path child = root.resolve("child.py");
		try (var stream = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(stream, child);
		}
		Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		var captures = new ConcurrentHashMap<String, ClaudeRetainedCapture>();
		var release = new CountDownLatch(1);
		ExecutionRecorder recorder = new ExecutionRecorder() {
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> evidence) {
				var delivery = journal.prepare(d);
				captures.put(d.executionId(), (ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
				return () -> {
					try {
						if (!release.await(10, TimeUnit.SECONDS))
							throw new AssertionError("admission blocked");
					}
					catch (InterruptedException ex) {
						throw new AssertionError(ex);
					}
					return delivery.get();
				};
			}
		};
		var provider = ClaudeClientProvider.builder()
			.claudePath(child.toString())
			.workingDirectory(root)
			.acquisitionDirectory(root.resolve("capture"), "2.1.316")
			.retainedTranscript(transcript)
			.build();
		List<AgentExecution> executions = new ArrayList<>();
		try (var client = AgentClient.builder(provider)
			.defaultTimeout(Duration.ofSeconds(5))
			.executionRecorder(recorder)
			.build(); var session = client.sessions().create(s -> {
			})) {
			for (String prompt : List.of("first", "second")) {
				var e = session.goal(prompt).runAsync();
				assertThat(e.result().getResult()).isEqualTo(prompt);
				executions.add(e);
			}
			assertThat(executions.getFirst().recording().delivery()).isNotInstanceOf(TerminalDelivery.Completed.class);
			await(() -> captures.size() == 2
					&& journal.attachment(executions.getLast().executionId()).decisionId() != null);
			assertThatThrownBy(
					() -> journal.attach(executions.getFirst().executionId(), root.resolve("not-yet-sealed")))
				.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> captures.get(executions.getFirst().executionId())
				.writeGeneration(executions.stream().map(e -> e.decision().orElseThrow()).toList()))
				.isInstanceOf(IllegalStateException.class);
			release.countDown();
			for (var e : executions) {
				JournalExecutionRecorderTest.awaitRecording(e);
				assertThat(e.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
			}
		}
		finally {
			release.countDown();
		}
		assertThat(Files.readAllLines(root.resolve("launches.jsonl"))).hasSize(1);
		assertThat(Files.readAllLines(root.resolve("prompts.jsonl"))).hasSize(2);
		var capture = captures.get(executions.getFirst().executionId());
		assertThat(captures.get(executions.getLast().executionId())).isSameAs(capture);
		return new Run(capture.writeGeneration(executions.stream().map(e -> e.decision().orElseThrow()).toList()),
				executions);
	}

	private List<Path> events(Path store) throws IOException {
		try (var files = Files.walk(store.resolve("prepared"))) {
			return files.filter(p -> p.getFileName().toString().equals("events.jsonl")).toList();
		}
	}

	private static void await(java.util.function.BooleanSupplier condition) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < until)
			Thread.sleep(5);
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private record Run(Path manifest, List<AgentExecution> executions) {
	}

}
