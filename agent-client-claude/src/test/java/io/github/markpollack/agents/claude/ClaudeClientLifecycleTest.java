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

@Timeout(30)
class ClaudeClientLifecycleTest {

	@TempDir
	Path directory;

	private ClaudeClientProvider provider() throws Exception {
		Path script = directory.resolve("child.py");
		if (!Files.exists(script)) {
			try (var source = getClass().getResourceAsStream("/retained-child.py")) {
				Files.copy(source, script);
			}
			Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		}
		return ClaudeClientProvider.builder()
			.workingDirectory(directory)
			.claudePath(script.toString())
			.tools(List.of())
			.build();
	}

	private AgentClient client() throws Exception {
		return AgentClient.builder(provider()).defaultTimeout(Duration.ofSeconds(8)).build();
	}

	@Test
	void oneShotUsesProductionLifecycleAndClosesObservedProcess() throws Exception {
		try (var client = client()) {
			var execution = client.goal("first").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("first");
			assertThat(execution.awaitTermination(Duration.ofSeconds(2)).cleanupConfirmed()).isTrue();
		}
		assertDead();
	}

	@Test
	void retainedFollowupReferenceProgressAndRefreshKeepExactHistory() throws Exception {
		var binding = ConnectionBinding.withoutMcp(Map.of("TOKEN_FILE", "unchanged"));
		var events = new CopyOnWriteArrayList<AgentClientEvent>();
		try (var client = client(); var session = client.sessions().create(s -> s.binding(binding))) {
			assertThat(session.capabilities().progress().supported()).isTrue();
			var first = session.goal("first").onEvent(events::add).runAsync();
			first.result();
			String connection = first.decision().orElseThrow().connectionId().orElseThrow();
			var ref = session.providerReference().orElseThrow();
			assertThat(ref.nativeId()).isEqualTo("fixture-history");
			assertThat(session.goal("second").runAsync().result().getResult()).isEqualTo("second");
			assertThat(Files.readAllLines(directory.resolve("launches.jsonl"))).hasSize(1);
			var refreshed = session.goal("third").refreshBinding(binding).runAsync();
			assertThat(refreshed.result().agentResponse().getMetadata().getProviderFields())
				.containsEntry("sessionReconnected", true);
			assertThat(refreshed.decision().orElseThrow().connectionId().orElseThrow()).isNotEqualTo(connection);
			assertThat(session.providerReference()).contains(ref);
			assertThat(Files.readString(directory.resolve("launches.jsonl"))).contains("--resume", "fixture-history",
					"unchanged");
			assertThatThrownBy(() -> client.sessions().load(ref, s -> {
			})).isInstanceOf(UnsupportedOperationException.class);
			long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
			while (events.size() < 2 && System.nanoTime() < end)
				Thread.sleep(1);
			assertThat(events).anyMatch(event -> event.kind() == EventKind.PROGRESS);
		}
		assertDead();
	}

	@Test
	void targetedCancellationWithEitherFlagReconnectsWithoutReplay() throws Exception {
		for (boolean flag : new boolean[] { false, true }) {
			Files.deleteIfExists(directory.resolve("waiting"));
			try (var client = client(); var session = client.sessions().create(s -> {
			})) {
				session.goal("first").runAsync().result();
				String logical = session.id();
				var execution = session.goal("wait").runAsync();
				awaitFile("waiting");
				assertThat(execution.cancel(flag)).isTrue();
				assertThatThrownBy(execution::result).isInstanceOf(CancellationException.class);
				assertThat(execution.awaitTermination(Duration.ofSeconds(9)).cleanupConfirmed()).isTrue();
				assertThat(session.goal("after").runAsync().result().getResult()).isEqualTo("after");
				assertThat(session.id()).isEqualTo(logical);
			}
			assertDead();
		}
		assertThat(Files.readAllLines(directory.resolve("prompts.jsonl"))).hasSize(6);
	}

	@Test
	void oldCompletedTargetCannotStopTheFollowingTurn() throws Exception {
		var connection = provider().open(DefaultAgentOptions.builder().build());
		try {
			var first = connection.prepare("first");
			first.call(new io.github.markpollack.agents.model.AgentTaskRequest("first", directory,
					new DefaultAgentOptions()));
			var next = connection.prepare("next");
			first.stop(true);
			assertThat(next
				.call(new io.github.markpollack.agents.model.AgentTaskRequest("second", directory,
						new DefaultAgentOptions()))
				.isSuccessful()).isTrue();
		}
		finally {
			connection.close();
		}
		assertDead();
	}

	@Test
	void missingTerminalAndChangedHistoryFailWithoutReplay() throws Exception {
		for (String prompt : List.of("eof", "wrong-history")) {
			try (var client = client(); var session = client.sessions().create(s -> {
			})) {
				session.goal("first").runAsync().result();
				var execution = session.goal(prompt).runAsync();
				assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
				assertThat(execution.awaitTermination(Duration.ofSeconds(9)).cleanupConfirmed()).isTrue();
			}
			assertDead();
		}
	}

	@Test
	void sourceMaterializationIsOptionalAndRunsAfterResult() throws Exception {
		provider();
		var captures = directory.resolve("captures");
		var nativeProvider = ClaudeClientProvider.builder()
			.workingDirectory(directory)
			.claudePath(directory.resolve("child.py").toString())
			.maxBudgetUsd(0.5)
			.acquisitionDirectory(captures, "2.1.316")
			.build();
		var evidence = new CompletableFuture<ClaudeAcquisition>();
		ExecutionRecorder recorder = new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision decision) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision decision,
					Map<String, Object> sources) {
				evidence.complete((ClaudeAcquisition) sources.get("claudeAcquisition"));
				return () -> "test-ack";
			}
		};
		try (var client = AgentClient.builder(nativeProvider).executionRecorder(recorder).build()) {
			var execution = client.goal("one shot").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			var acquired = evidence.get(3, TimeUnit.SECONDS);
			assertThat(acquired.invocationId()).isEqualTo(execution.executionId());
			assertThat(acquired.complete()).isFalse(); // Fixture has no native
														// transcript.
			assertThat(Files.readString(acquired.manifest()))
				.contains(execution.decision().orElseThrow().connectionId().orElseThrow());
			assertThat(Files.readString(acquired.manifest().getParent().resolve("stdin.bin"))).contains("one shot");
			assertThat(Files.readString(directory.resolve("launches.jsonl"))).contains("--max-budget-usd", "0.5");
			var failed = client.goal("eof").runAsync();
			assertThatThrownBy(failed::result).isInstanceOf(AgentExecutionException.class);
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (!(failed.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime() < until)
				Thread.sleep(5);
			assertThat(failed.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
			assertThat(failed.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.INCOMPLETE);
		}
		assertDead();
	}

	@Test
	void stoppedUnsentTurnCannotDispatchAndNoHistoryIsInvented() throws Exception {
		var connection = provider().open(DefaultAgentOptions.builder().build());
		try {
			var stopped = connection.prepare("never");
			stopped.stop(false);
			assertThatThrownBy(() -> stopped.call(new io.github.markpollack.agents.model.AgentTaskRequest("never",
					directory, new DefaultAgentOptions())))
				.isInstanceOf(CancellationException.class);
			var next = connection.prepare("next");
			assertThat(next
				.call(new io.github.markpollack.agents.model.AgentTaskRequest("first", directory,
						new DefaultAgentOptions()))
				.isSuccessful()).isTrue();
			assertThatThrownBy(() -> next.call(new io.github.markpollack.agents.model.AgentTaskRequest("repeat",
					directory, new DefaultAgentOptions())))
				.isInstanceOf(IllegalStateException.class);
		}
		finally {
			connection.close();
		}
		assertThat(Files.readAllLines(directory.resolve("prompts.jsonl"))).hasSize(1);
	}

	@Test
	void failedAcquisitionAndInvalidBudgetDoNotSendTask() {
		assertThatThrownBy(() -> ClaudeClientProvider.builder().maxBudgetUsd(Double.NaN))
			.isInstanceOf(IllegalArgumentException.class);
		var provider = ClaudeClientProvider.builder()
			.workingDirectory(directory)
			.claudePath(directory.resolve("absent").toString())
			.build();
		try (var client = AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(200)).build()) {
			assertThatThrownBy(() -> client.sessions().create(s -> {
			})).isInstanceOf(AgentOperationException.class);
		}
		assertThat(directory.resolve("prompts.jsonl")).doesNotExist();
	}

	private void awaitFile(String name) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!Files.exists(directory.resolve(name)) && System.nanoTime() < end)
			Thread.sleep(5);
		assertThat(directory.resolve(name)).exists();
	}

	private void assertDead() throws Exception {
		try (var files = Files.list(directory)) {
			for (var file : files.filter(p -> p.getFileName().toString().startsWith("pid-")).toList()) {
				long pid = Long.parseLong(Files.readString(file));
				assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("owned PID %s", pid)
					.isFalse();
			}
		}
	}

}
