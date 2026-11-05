/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.client.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Co-supervisor finding 1, reproduced with deterministic synchronization: the next turn's
 * preparation observes the previous turn's native boundary on the capture's monitor, the
 * same monitor the recorder lane holds while sealing. While sealing holds it, preparation
 * waits; what the lifecycle then does with its setup budget and cleanup is established
 * here, not assumed.
 */
@Timeout(60)
class CodexSealingContentionTest {

	@TempDir
	Path root;

	Path script;

	@BeforeEach
	void setup() throws Exception {
		this.script = root.resolve("child.py");
		try (var in = getClass().getResourceAsStream("/codex-child.py")) {
			Files.copy(in, script);
		}
		try (var in = getClass().getResourceAsStream("/codex-native-template.jsonl")) {
			Files.copy(in, root.resolve("codex-native-template.jsonl"));
		}
		Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
	}

	private DefaultAgentOptions options(Path home) {
		return DefaultAgentOptions.builder()
			.workingDirectory(home.toString())
			.timeout(Duration.ofSeconds(8))
			.environmentVariables(Map.of("FIXTURE_SESSIONS", home.resolve("sessions").toString()))
			.model("fixture-model")
			.build();
	}

	@Test
	void preparationWaitsBehindSealingUntilSetupTimeoutThenRecoversOnceSealingEnds() throws Exception {
		Path home = Files.createDirectories(root.resolve("contention"));
		var captures = new LinkedBlockingQueue<CodexWitness>();
		var sealed = new LinkedBlockingQueue<CodexAcquisition>();
		var provider = CodexClientProvider.builder()
			.codexPath(script.toString())
			.workingDirectory(home)
			.nativeSessionsDirectory(home.resolve("sessions"))
			.acquisitionDirectory(home.resolve("captures"), "0.166.0")
			.skipGitCheck(true)
			.captureObserver(captures::add)
			.build();
		var recorder = new ExecutionRecorder() {
			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d) {
				throw new AssertionError();
			}

			@Override
			public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> values) {
				sealed.add((CodexAcquisition) values.get("codexAcquisition"));
				return () -> "ack";
			}
		};
		var holding = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		Thread sealing = null;
		try (var client = AgentClient.builder(provider)
			.defaultOptions(options(home))
			.defaultWorkingDirectory(home)
			.setupTimeout(Duration.ofMillis(700))
			.executionRecorder(recorder)
			.build(); var session = client.sessions().create(s -> s.workingDirectory(home).options(options(home)))) {
			var first = session.goal("first").runAsync();
			assertThat(first.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:first");
			String thread = session.providerReference().orElseThrow().nativeId();
			CodexWitness capture = captures.poll(5, TimeUnit.SECONDS);
			assertThat(capture).isNotNull();
			// Sealing in progress: the recorder lane owns the capture monitor while it
			// writes.
			sealing = new Thread(() -> {
				synchronized (capture) {
					holding.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
			}, "sealing-in-progress");
			sealing.start();
			assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

			var second = session.goal("second").runAsync();
			assertThatThrownBy(() -> second.result(Duration.ofSeconds(5))).isInstanceOf(AgentExecutionException.class);
			var outcome = second.decision().orElseThrow().outcome();
			assertThat(outcome).isInstanceOf(ExecutionOutcome.Failed.class);
			assertThat(((ExecutionOutcome.Failed) outcome).failure().code()).isEqualTo(FailureCode.SETUP_TIMEOUT);
			assertThat(second.inspect().providerTurnDispatched()).isFalse();
			assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).as("no second process while sealing")
				.hasSize(1);
			// Cleanup while preparation is still blocked: the setup thread cannot be
			// released, so
			// termination is not observable within a bounded wait and cleanup stays
			// unconfirmed.
			assertThatThrownBy(() -> second.awaitTermination(Duration.ofMillis(500)))
				.isInstanceOf(TimeoutException.class);
			assertThat(second.inspect().cleanup().state()).isNotEqualTo(CleanupState.CONFIRMED);

			release.countDown();
			sealing.join(5_000);
			var settled = second.awaitTermination(Duration.ofSeconds(5));
			assertThat(settled.cleanupConfirmed()).as("cleanup confirmed once sealing ended").isTrue();
			assertThat(sealed.poll(5, TimeUnit.SECONDS)).as("first turn sealed, before or after the hold").isNotNull();

			var third = session.goal("third").runAsync();
			assertThat(third.result(Duration.ofSeconds(5)).getResult()).isEqualTo("answer:third");
			assertThat(session.providerReference().orElseThrow().nativeId()).isEqualTo(thread);
			assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(2);
			assertThat(Files.readAllLines(home.resolve("launches.jsonl")).get(1)).contains("resume", thread);
		}
		finally {
			release.countDown();
			if (sealing != null)
				sealing.join(5_000);
		}
	}

}
