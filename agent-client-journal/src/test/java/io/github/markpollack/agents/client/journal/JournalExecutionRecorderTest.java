/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;

@Timeout(15)
class JournalExecutionRecorderTest {

	@TempDir
	Path root;

	static AgentExecutionProvider provider(String finish) {
		return options -> new AgentExecutionProvider.Connection() {
			@Override
			public AgentExecutionProvider.Turn prepare(String id) {
				return new AgentExecutionProvider.Turn() {
					@Override
					public AgentResponse call(AgentTaskRequest request) {
						return new AgentResponse(List
							.of(new AgentGeneration(request.goal(), new AgentGenerationMetadata(finish, Map.of()))));
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
	}

	@Test
	void actualClientRecordsAndReaderInterpretsTheOriginalDecision() throws Exception {
		var recorder = new JournalExecutionRecorder(root, "test-client");
		try (var client = AgentClient.builder(provider("SUCCESS")).executionRecorder(recorder).build()) {
			var execution = client.goal("hello")
				.correlation(new ApplicationCorrelation(Optional.of("exp\n\"\\😀"), Optional.of("run"),
						Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
				.runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			awaitRecording(execution);
			var decision = execution.decision().orElseThrow();
			String original = ((TerminalDelivery.Completed) execution.recording().delivery()).bindingId();
			var frozen = recorder.prepare(decision);
			assertThat(frozen.get()).isEqualTo(original);
			assertThat(frozen.get()).isEqualTo(original);
			Path events;
			try (var files = Files.walk(root.resolve("prepared"))) {
				events = files.filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
			}
			var pkg = PackageReader.open(events);
			assertThat(pkg.diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.DEFECT)).isEmpty();
			assertThat(TerminalBindingReader.read(pkg.declarations).decision())
				.isEqualTo(DecisionEncoding.decision(decision));
			assertThat(execution.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.UNAVAILABLE);
		}
	}

	@Test
	void lostReplyRemainsUncertainAndExplicitSamePayloadRecoversOriginalAck() throws Exception {
		var calls = new AtomicInteger();
		var receiver = new TerminalReceiver(root, "client", WriterOptions.defaults().withFaults((operation, detail) -> {
			if (operation.equals("terminal-reply") && calls.getAndIncrement() == 0)
				throw new java.io.IOException("lost reply");
		}));
		var recorder = new JournalExecutionRecorder(receiver, root, "client");
		try (var client = AgentClient.builder(provider("SUCCESS")).executionRecorder(recorder).build()) {
			var execution = client.goal("effects").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			awaitRecording(execution);
			var failure = (TerminalDelivery.Failed) execution.recording().delivery();
			assertThat(failure.acceptanceUnknown()).isTrue();
			assertThat(failure.retryable()).isFalse();
			var retry = recorder.prepare(execution.decision().orElseThrow());
			String ack = retry.get();
			assertThat(retry.get()).isEqualTo(ack);
			assertThat(execution.result().isSuccessful()).isTrue();
		}
	}

	@Test
	void changedNamespaceConflictsWithoutChangingOutcome() throws Exception {
		var receiver = new TerminalReceiver(root, "original", WriterOptions.defaults());
		var recorder = new JournalExecutionRecorder(receiver, root, "different");
		try (var client = AgentClient.builder(provider("SUCCESS")).executionRecorder(recorder).build()) {
			var execution = client.goal("success").runAsync();
			execution.result();
			awaitRecording(execution);
			var failed = (TerminalDelivery.Failed) execution.recording().delivery();
			assertThat(failed.failure().code()).isEqualTo(FailureCode.RECORDING_CONFLICT);
			assertThat(failed.retryable()).isFalse();
			assertThat(execution.result().isSuccessful()).isTrue();
		}
	}

	@Test
	void stopAndFailedOutcomesHaveExactCodecMeaning() throws Exception {
		try (var client = AgentClient.create(provider("STOP"))) {
			var execution = client.goal("refusal").runAsync();
			assertThat(execution.result().isSuccessful()).isFalse();
			String encoded = DecisionEncoding.decision(execution.decision().orElseThrow());
			assertThat(encoded).contains("\"kind\":\"STOP\"", "\"failure\":null");
		}
		AgentExecutionProvider failed = options -> {
			throw new IllegalArgumentException("setup\tfailed");
		};
		try (var client = AgentClient.create(failed)) {
			var execution = client.goal("failed").runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
			assertThat(DecisionEncoding.decision(execution.decision().orElseThrow())).contains("\"kind\":\"FAILED\"",
					"SETUP_FAILED", "\\u0009");
		}
	}

	@Test
	void codecRejectsMalformedUnicodeAndOutOfRangeInstants() {
		assertThatThrownBy(() -> DecisionEncoding.json("\ud800")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DecisionEncoding.json("\udc00")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DecisionEncoding.instant(Instant.parse("+10000-01-01T00:00:00Z")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DecisionEncoding.json(new Object())).isInstanceOf(IllegalArgumentException.class);
	}

	static void awaitRecording(AgentExecution execution) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed)
				&& !(execution.recording().delivery() instanceof TerminalDelivery.Failed)
				&& System.nanoTime() < deadline)
			Thread.yield();
		assertThat(execution.recording().delivery()).isNotInstanceOf(TerminalDelivery.Pending.class)
			.isNotInstanceOf(TerminalDelivery.Processing.class);
	}

}
