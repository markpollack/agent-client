/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.claude.BaseRunRecorder;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.config.ClaudeCliDiscovery;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test verifying the full exhaust capture pipeline:
 * {@link ClaudeAgentModel} → messageListener → {@link SessionLogParser} →
 * {@link PhaseCapture} → journal-core {@link Run} with events.
 *
 * <p>
 * Requires Claude CLI installed and authenticated. Skips gracefully if unavailable.
 * Cannot run from within a Claude Code session (nesting guard) — use
 * {@code ~/scripts/claude-run.sh} or run from terminal/CI.
 * </p>
 */
class ClaudeAgentJournalCaptureIT {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeAgentJournalCaptureIT.class);

	@TempDir
	Path testWorkspace;

	private ClaudeAgentModel claudeAgentModel;

	private final List<ParsedMessage> capturedMessages = new CopyOnWriteArrayList<>();

	private Path traceDir;

	@BeforeEach
	void setUp() throws IOException {
		assumeTrue(isClaudeCliAvailable(), "Claude CLI must be available for integration tests");

		// Create a file for Claude to read — triggers tool use
		Files.writeString(this.testWorkspace.resolve("greeting.txt"), "Hello from the integration test!\n");

		ClaudeAgentOptions options = ClaudeAgentOptions.builder()
			.model("claude-haiku-4-5-20251001")
			.yolo(true)
			.maxTurns(3)
			.build();

		this.traceDir = this.testWorkspace.resolve(".traces");

		this.claudeAgentModel = ClaudeAgentModel.builder()
			.workingDirectory(this.testWorkspace)
			.timeout(Duration.ofMinutes(2))
			.defaultOptions(options)
			.messageListener(this.capturedMessages::add)
			.traceDir(this.traceDir)
			.build();

		assumeTrue(this.claudeAgentModel.isAvailable(), "Claude agent must be available");
	}

	@AfterEach
	void tearDown() {
		if (this.claudeAgentModel != null) {
			this.claudeAgentModel.close();
		}
	}

	@Test
	@DisplayName("Full pipeline: ClaudeAgentModel → messageListener → SessionLogParser → PhaseCapture with thinking, tool calls, tokens")
	void fullExhaustCapturePipeline() throws IOException {
		// Step 1: Invoke Claude with a task that forces tool use
		AgentTaskRequest request = AgentTaskRequest
			.builder("Read the file greeting.txt and tell me what it says. Use the Read tool.", this.testWorkspace)
			.build();

		logger.info("Calling ClaudeAgentModel.call() with messageListener...");
		AgentResponse response = this.claudeAgentModel.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResults()).isNotEmpty();
		logger.info("Agent response: {}", response.getResults().get(0).getOutput());

		// Step 1b: Verify traceDir produced a JSONL trace file
		String tracePath = (String) response.getMetadata().getProviderFields().get("tracePath");
		assertThat(tracePath).as("tracePath should be in providerFields").isNotNull();
		Path traceFile = Path.of(tracePath);
		assertThat(traceFile).exists();
		List<String> traceLines = Files.readAllLines(traceFile);
		logger.info("Trace file: {} ({} events)", traceFile.getFileName(), traceLines.size());
		assertThat(traceLines).as("Trace file should have events").isNotEmpty();
		assertThat(traceLines).last().asString().contains("\"type\":\"result\"");
		// Should have tool_use events since we asked Claude to read a file
		assertThat(traceLines.stream().anyMatch(l -> l.contains("\"type\":\"tool_use\"")))
			.as("Should have tool_use trace events")
			.isTrue();

		// Step 2: Verify raw message capture
		assertThat(this.capturedMessages).as("messageListener should receive ParsedMessages").isNotEmpty();
		logger.info("Captured {} raw ParsedMessage(s)", this.capturedMessages.size());

		long regularCount = this.capturedMessages.stream().filter(ParsedMessage::isRegularMessage).count();
		logger.info("  Regular messages: {}", regularCount);
		assertThat(regularCount).as("Should have regular messages").isGreaterThan(0);

		// Step 3: Feed captured messages through SessionLogParser
		PhaseCapture capture = SessionLogParser.parse(this.capturedMessages.iterator(), "integration-test",
				"Read greeting.txt");

		logger.info("PhaseCapture results:");
		logger.info("  inputTokens:    {}", capture.inputTokens());
		logger.info("  outputTokens:   {}", capture.outputTokens());
		logger.info("  thinkingTokens: {}", capture.thinkingTokens());
		logger.info("  totalCostUsd:   {}", capture.totalCostUsd());
		logger.info("  numTurns:       {}", capture.numTurns());
		logger.info("  sessionId:      {}", capture.sessionId());
		logger.info("  isError:        {}", capture.isError());
		logger.info("  toolUses:       {}", capture.toolUses().size());
		logger.info("  thinkingBlocks: {}", capture.thinkingBlocks().size());
		logger.info("  textOutput len: {}", capture.textOutput().length());

		// Step 4: Assert the capture has real data
		assertThat(capture.isError()).as("Should not be an error").isFalse();
		assertThat(capture.inputTokens()).as("Should have input tokens").isGreaterThan(0);
		assertThat(capture.outputTokens()).as("Should have output tokens").isGreaterThan(0);
		assertThat(capture.totalCostUsd()).as("Should have cost > 0").isGreaterThan(0.0);
		assertThat(capture.numTurns()).as("Should have at least 1 turn").isGreaterThanOrEqualTo(1);
		assertThat(capture.sessionId()).as("Should have a session ID").isNotNull();
		assertThat(capture.textOutput()).as("Should have text output").isNotBlank();

		// Tool use: we asked Claude to read a file, so it should have used Read
		assertThat(capture.hasToolUses()).as("Should have tool uses (Read)").isTrue();
		logger.info("  Tool names: {}", capture.toolUses().stream().map(t -> t.name()).toList());

		// Step 5: Feed PhaseCapture through BaseRunRecorder into journal-core
		InMemoryStorage storage = new InMemoryStorage();
		Journal.configure(storage);

		Run run = Journal.run("capture-it").task("e2e-test").start();
		TestRunRecorder recorder = new TestRunRecorder(run);
		recorder.recordPhase(capture);
		run.finish(RunStatus.FINISHED);

		// Verify events were recorded in the Run
		List<JournalEvent> events = storage.loadEvents("capture-it", run.id());
		logger.info("Journal events recorded: {}", events.size());
		for (JournalEvent event : events) {
			logger.info("  {}: {}", event.getClass().getSimpleName(), event);
		}

		assertThat(events).as("Should have journal events").isNotEmpty();

		// Should have at least: StateChangeEvent + LLMCallEvent + ToolCallEvent(s)
		assertThat(events.stream().filter(e -> e instanceof LLMCallEvent).count()).as("Should have LLMCallEvent(s)")
			.isGreaterThanOrEqualTo(1);

		assertThat(events.stream().filter(e -> e instanceof ToolCallEvent).count()).as("Should have ToolCallEvent(s)")
			.isGreaterThanOrEqualTo(1);

		// Verify the LLMCallEvent has real token data
		LLMCallEvent llmEvent = events.stream()
			.filter(e -> e instanceof LLMCallEvent)
			.map(e -> (LLMCallEvent) e)
			.findFirst()
			.orElseThrow();
		assertThat(llmEvent.tokenUsage().inputTokens()).as("LLMCallEvent should have input tokens").isGreaterThan(0);
		assertThat(llmEvent.cost()).as("LLMCallEvent should have cost").isNotNull();

		logger.info("End-to-end capture pipeline verified successfully!");
	}

	@Test
	@DisplayName("Trace v2: valid JSON lines, content capture, >60KB truncation with source pointer, transcript archival")
	void traceV2ContentCapture() throws IOException {
		// A >60KB file forces tool_result truncation (the riskiest new behavior)
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 800; i++) {
			big.append("line-").append(i).append(" ").append("x".repeat(95)).append("\n");
		}
		Files.writeString(this.testWorkspace.resolve("big.txt"), big.toString());

		AgentTaskRequest request = AgentTaskRequest
			.builder("Think hard about this task: read the file big.txt with the Read tool, "
					+ "then state how many lines it has.", this.testWorkspace)
			.build();

		AgentResponse response = this.claudeAgentModel.call(request);
		assertThat(response.getResults()).isNotEmpty();

		String tracePath = (String) response.getMetadata().getProviderFields().get("tracePath");
		assertThat(tracePath).isNotNull();
		List<String> lines = Files.readAllLines(Path.of(tracePath));

		// JSON-validity sweep: every line must parse — proves zero silent-skip lines
		// (the loaders.py malformed-line regression)
		ObjectMapper mapper = new ObjectMapper();
		List<JsonNode> nodes = new ArrayList<>();
		for (String line : lines) {
			nodes.add(mapper.readTree(line));
		}

		// Schema v2 header first
		assertThat(nodes.get(0).get("type").asText()).isEqualTo("header");
		assertThat(nodes.get(0).get("schemaVersion").asInt()).isEqualTo(2);

		// Result line enrichment
		JsonNode result = nodes.get(nodes.size() - 1);
		assertThat(result.get("type").asText()).isEqualTo("result");
		assertThat(result.get("sessionId").asText()).isNotBlank();
		assertThat(result.has("cacheReadInputTokens")).isTrue();
		assertThat(result.has("durationApiMs")).isTrue();

		// Oversized tool_result: head(60KB) + canonical contentLength + source pointer
		JsonNode truncated = nodes.stream()
			.filter(n -> "tool_result".equals(n.path("type").asText()) && n.path("truncated").asBoolean())
			.findFirst()
			.orElse(null);
		assertThat(truncated).as("Read of a >60KB file should produce a truncated tool_result").isNotNull();
		assertThat(truncated.get("contentLength").asInt()).isGreaterThan(60_000);
		assertThat(truncated.get("content").asText()).hasSize(60_000);
		assertThat(truncated.get("source").get("kind").asText()).isEqualTo("file_path");
		assertThat(truncated.get("source").get("value").asText()).endsWith("big.txt");

		// Thinking faithfulness: structural assertions only — content may legitimately
		// be redacted upstream (thinking:"" + signature); we record what arrived
		List<JsonNode> thinking = nodes.stream().filter(n -> "thinking".equals(n.path("type").asText())).toList();
		logger.info("Thinking lines: {}", thinking.size());
		for (JsonNode n : thinking) {
			assertThat(n.has("length")).isTrue();
			assertThat(n.has("content")).isTrue();
			assertThat(n.has("hasSignature")).isTrue();
		}

		// Transcript archival is best-effort: when reported, the archive must exist;
		// when absent, log only (CI must never require archive success)
		Object transcriptPath = response.getMetadata().getProviderFields().get("transcriptPath");
		if (transcriptPath != null) {
			assertThat(Path.of((String) transcriptPath)).exists();
			logger.info("Transcript archived: {}", transcriptPath);
		}
		else {
			logger.warn("Transcript not archived (best-effort) — check slug/session resolution diagnostics above");
		}
	}

	/**
	 * Minimal RunRecorder for testing. Extends BaseRunRecorder to get the shared phase
	 * recording logic.
	 */
	static class TestRunRecorder extends BaseRunRecorder {

		TestRunRecorder(Run run) {
			this.currentRun = run;
		}

	}

	private boolean isClaudeCliAvailable() {
		try {
			return ClaudeCliDiscovery.isClaudeCliAvailable();
		}
		catch (Exception ex) {
			return false;
		}
	}

}
