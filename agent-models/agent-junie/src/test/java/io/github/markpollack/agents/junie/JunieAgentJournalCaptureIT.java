/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.junie.JuniePhaseCapture;
import io.github.markpollack.journal.junie.JunieRunRecorder;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves a journal is actually produced for Junie: {@code JunieAgentModel} → ACP →
 * Junie's native {@code events.jsonl} → {@link JuniePhaseCapture} →
 * {@code AgentClientResponse.getPhaseCapture()} → journal-core events.
 *
 * <p>
 * This test is not optional. This project has shipped providers that run correctly and
 * silently produce no journal, because nothing is wrong — capture is merely absent, and
 * absence does not fail a build. Only an assertion that the journal exists catches that,
 * which is why it is asserted end to end here rather than inferred from the presence of a
 * dependency.
 */
class JunieAgentJournalCaptureIT {

	private static final Logger logger = LoggerFactory.getLogger(JunieAgentJournalCaptureIT.class);

	@TempDir
	Path workspace;

	private JunieAgentModel junie;

	@BeforeEach
	void setUp() throws IOException {
		JunieAgentOptions.Builder options = JunieAgentOptions.builder().timeout(Duration.ofMinutes(8));
		JunieTestCredentials.apply(options);

		this.junie = JunieAgentModel.builder().defaultOptions(options.build()).build();

		assumeTrue(this.junie.isAvailable(), "Junie CLI must be installed and on PATH");
		assumeTrue(JunieTestCredentials.available(), "Junie needs JUNIE_API_KEY, or a BYOK key, to reach a model");

		// A real bug, and a test that proves it: forces inspection, an edit, and a
		// command. A prompt that only needs an answer would not exercise tool capture.
		Files.writeString(this.workspace.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
		Files.writeString(this.workspace.resolve("test_calc.py"), """
				from calc import add

				if __name__ == "__main__":
				    assert add(2, 3) == 5, f"add(2,3) returned {add(2, 3)}"
				    print("ALL TESTS PASSED")
				""");
	}

	@Test
	@DisplayName("Full pipeline: Junie over ACP → events.jsonl → JuniePhaseCapture → journal-core events")
	void producesAJournal() throws IOException {
		AgentClientResponse response = AgentClient.create(this.junie)
			.goal("Inspect this project, fix the bug in calc.py, and run `python3 test_calc.py` to prove it passes.")
			.workingDirectory(this.workspace)
			.run();

		assertThat(response).as("AgentClientResponse should be returned").isNotNull();

		// Junie must actually have changed the file — its own account of what it did is
		// not evidence. This is the assertion that caught Antigravity writing to a shared
		// scratch directory while reporting success.
		String calc = Files.readString(this.workspace.resolve("calc.py"));
		assertThat(calc).as("Junie should have fixed the operator").contains("a + b");

		JuniePhaseCapture capture = response.getPhaseCapture();
		assertThat(capture).as("getPhaseCapture() must return a capture, not null").isNotNull();

		logger.info("JuniePhaseCapture: llmCalls={} toolUses={} in={} out={} cacheRead={} costUsd={} state={}",
				capture.numLlmCalls(), capture.toolUses().size(), capture.inputTokens(), capture.outputTokens(),
				capture.cacheReadTokens(), capture.totalCostUsd(), capture.taskState());
		logger.info("Tool names: {}", capture.toolUses().stream().map(t -> t.name()).toList());

		assertThat(capture.numLlmCalls()).as("at least one LLM call captured").isGreaterThanOrEqualTo(1);
		assertThat(capture.hasToolUses()).as("at least one tool/action captured").isTrue();

		// Token information, where Junie emitted it. Junie reports no thinking tokens at
		// all, so that field is deliberately absent rather than asserted as zero.
		assertThat(capture.inputTokens()).as("input tokens").isGreaterThan(0);
		assertThat(capture.outputTokens()).as("output tokens").isGreaterThan(0);
		assertThat(capture.totalCostUsd()).as("Junie reports real per-call cost").isGreaterThan(0.0);

		// Feed the capture into journal-core and prove events land.
		InMemoryStorage storage = new InMemoryStorage();
		Journal.configure(storage);
		Run run = Journal.run("junie-capture-it").task("e2e-test").start();
		new JunieRunRecorder(run).recordPhase(capture);
		run.finish(RunStatus.FINISHED);

		List<JournalEvent> events = storage.loadEvents("junie-capture-it", run.id());
		logger.info("Journal events recorded: {}", events.size());
		assertThat(events).as("journal events should be recorded").isNotEmpty();
		assertThat(events.stream().filter(e -> e instanceof LLMCallEvent).count()).as("LLMCallEvent(s)")
			.isGreaterThanOrEqualTo(1);
		assertThat(events.stream().filter(e -> e instanceof ToolCallEvent).count()).as("ToolCallEvent(s)")
			.isGreaterThanOrEqualTo(1);

		LLMCallEvent llm = events.stream()
			.filter(e -> e instanceof LLMCallEvent)
			.map(e -> (LLMCallEvent) e)
			.findFirst()
			.orElseThrow();
		assertThat(llm.tokenUsage().inputTokens()).as("LLMCallEvent should carry real tokens").isGreaterThan(0);
	}

	@Test
	@DisplayName("Capture is opt-out: disabling it leaves the run working and the capture absent")
	void captureCanBeTurnedOff() {
		JunieAgentOptions.Builder options = JunieAgentOptions.builder().timeout(Duration.ofMinutes(5));
		JunieTestCredentials.apply(options);
		JunieAgentModel noCapture = JunieAgentModel.builder()
			.defaultOptions(options.build())
			.captureEnabled(false)
			.build();

		AgentClientResponse response = AgentClient.create(noCapture)
			.goal("Reply with the word ready and do nothing else.")
			.workingDirectory(this.workspace)
			.run();

		assertThat(response).isNotNull();
		assertThat(response.getMetadata().getProviderFields()).doesNotContainKey("phaseCapture");
	}

}
