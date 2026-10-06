/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.claude.BaseRunRecorder;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.RunRecorder;
import io.github.markpollack.journal.claude.SubagentCapture;
import io.github.markpollack.journal.claude.ToolUseRecord;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.config.ClaudeCliDiscovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live proof that Claude Code sub-agent capture survives the real consumer path: Claude
 * CLI -> claude-code-sdk -> {@link ClaudeAgentModel} -> {@link PhaseCapture} ->
 * {@link RunRecorder} -> {@link JsonFileStorage} on disk -> fresh reader.
 *
 * <p>
 * Uses {@code ClaudeAgentModel.call} directly: the {@code AgentClient} facade lives in
 * the {@code agent-client} module, which depends on this one, so it cannot be used from
 * this module's tests. Set {@code -Dsubagent.forwardText=false} to run the same task
 * without {@code --forward-subagent-text}.
 * </p>
 */
class ClaudeAgentSubagentCaptureIT {

	private static final String PROMPT = "This is a capture test. In ONE assistant message, launch two general-purpose "
			+ "subagents in parallel with the Agent tool. Subagent one's prompt: 'First write one short sentence "
			+ "saying you are starting. Then run the shell command: echo alpha -- then write one short sentence "
			+ "stating exactly what it printed.' Subagent two's prompt: 'Run the shell command: echo beta -- then "
			+ "reply with exactly what it printed.' After both finish, reply with the single word done.";

	@TempDir
	Path workDir;

	@TempDir
	Path journalDir;

	@Test
	@DisplayName("Sub-agent tracks reach journal files through ClaudeAgentModel and reload from disk")
	void subagentCaptureEndToEnd() throws Exception {
		assumeTrue(ClaudeCliDiscovery.isClaudeCliAvailable(), "Claude CLI must be available");
		boolean forwardText = Boolean.parseBoolean(System.getProperty("subagent.forwardText", "true"));
		Path traceDir = this.workDir.resolve(".traces");

		ClaudeAgentOptions options = ClaudeAgentOptions.builder()
			.model("sonnet")
			.allowedTools(List.of("Agent", "Task", "Bash(echo:*)"))
			.forwardSubagentText(forwardText)
			.build();
		ClaudeAgentModel model = ClaudeAgentModel.builder()
			.workingDirectory(this.workDir)
			.timeout(Duration.ofMinutes(5))
			.defaultOptions(options)
			.traceDir(traceDir)
			.build();
		assumeTrue(model.isAvailable(), "Claude agent must be available");

		PhaseCapture capture;
		try {
			AgentResponse response = model.call(AgentTaskRequest.builder(PROMPT, this.workDir).build());
			capture = (PhaseCapture) response.getMetadata().getProviderFields().get("phaseCapture");
			fact("forwardSubagentText=" + forwardText + " model=sonnet");
			fact("final text: " + response.getResults().get(0).getOutput().strip());
		}
		finally {
			model.close();
		}
		assertThat(capture).isNotNull();

		// (i) sub-agent tracks in the capture
		fact("(i) subagentTracksAvailable=" + capture.subagentTracksAvailable() + " subagents="
				+ capture.subagents().size() + " reportedSubagentsSpawned=" + capture.reportedSubagentsSpawned());
		assertThat(capture.subagentTracksAvailable()).isTrue();
		assertThat(capture.subagents()).hasSize(2);
		for (SubagentCapture s : capture.subagents()) {
			fact("(i) subagent spawn=" + s.spawnToolUseId() + " parentSpawn=" + s.parentSpawnToolUseId() + " depth="
					+ s.depth() + " agentId=" + s.agentId() + " status=" + s.status() + " statusSource="
					+ s.statusSource() + " toolUses=" + s.toolUses().size() + " toolResults=" + s.toolResults().size()
					+ " turns=" + s.turns().size() + " textOutputNonEmpty="
					+ (s.textOutput() != null && !s.textOutput().isBlank()) + " textLen="
					+ (s.textOutput() == null ? 0 : s.textOutput().length()) + " usage=" + s.aggregateUsage().toMap()
					+ " tools=" + s.toolUses().stream().map(ToolUseRecord::name).toList());
			assertThat(s.agentId()).isNotBlank();
		}

		// (ii) main loop tool uses are only the spawns
		List<String> mainToolNames = capture.toolUses().stream().map(ToolUseRecord::name).toList();
		fact("(ii) main toolUses=" + mainToolNames);
		assertThat(mainToolNames).allMatch(n -> n.equals("Agent") || n.equals("Task"));
		assertThat(mainToolNames).doesNotContain("Bash");
		Set<String> spawnIds = capture.toolUses().stream().map(ToolUseRecord::id).collect(Collectors.toSet());
		for (SubagentCapture s : capture.subagents()) {
			assertThat(spawnIds).contains(s.spawnToolUseId());
		}

		// record to disk
		JsonFileStorage storage = new JsonFileStorage(this.journalDir);
		Journal.configure(storage);
		Run run = Journal.run("subagent-proof").task("capture").start();
		RunRecorder recorder = new RunRecorder(run, storage);
		recorder.recordPhase(capture);
		recorder.finish();
		String parentRunId = run.id();

		// (iii) fresh reader
		JsonFileStorage fresh = new JsonFileStorage(this.journalDir);
		List<RunData> runs = fresh.listRuns("subagent-proof");
		fact("(iii) runs on disk=" + runs.size() + " ids=" + runs.stream().map(RunData::id).toList());
		assertThat(runs).hasSize(1 + capture.subagents().size());
		RunData parent = runs.stream().filter(r -> r.id().equals(parentRunId)).findFirst().orElseThrow();
		List<JournalEvent> parentEvents = fresh.loadEvents("subagent-proof", parentRunId);
		Set<String> parentToolCallIds = parentEvents.stream()
			.filter(e -> e instanceof ToolCallEvent)
			.map(e -> ((ToolCallEvent) e).id())
			.collect(Collectors.toSet());
		List<String> parentToolNames = parentEvents.stream()
			.filter(e -> e instanceof ToolCallEvent)
			.map(e -> ((ToolCallEvent) e).toolName())
			.toList();
		fact("(iii) parent run=" + parentRunId + " status=" + parent.status() + " parentRunId=" + parent.parentRunId()
				+ " tags=" + parent.tags().toMap() + " tool_call events=" + parentToolNames + " derivedEvents="
				+ fresh.loadDerivedEvents("subagent-proof", parentRunId).size());
		assertThat(parentToolNames).doesNotContain("Bash");

		List<RunData> children = runs.stream().filter(r -> !r.id().equals(parentRunId)).toList();
		double costSum = llmCost(parentEvents);
		int[] tokSum = tokens(parentEvents);
		for (RunData child : children) {
			List<JournalEvent> ev = fresh.loadEvents("subagent-proof", child.id());
			LLMCallEvent llm = llm(ev);
			List<String> childTools = ev.stream()
				.filter(e -> e instanceof ToolCallEvent)
				.map(e -> ((ToolCallEvent) e).toolName())
				.toList();
			String spawn = child.config().get(BaseRunRecorder.CONFIG_SPAWN_TOOL_USE_ID, String.class);
			fact("(iii)(v) child run=" + child.id() + " parentRunId=" + child.parentRunId() + " status="
					+ child.status() + " tags=" + child.tags().toMap() + " spawnToolUseId=" + spawn
					+ " summary.subagent.status=" + child.summary().get("subagent.status", String.class)
					+ " summary.subagent.statusSource=" + child.summary().get("subagent.statusSource", String.class)
					+ " tool_call events=" + childTools + " llm_call cost="
					+ (llm == null ? "none" : llm.totalCostUsd()) + " llm meta="
					+ (llm == null ? "none" : llm.metadata()) + " tokens="
					+ (llm == null ? "none" : llm.tokenUsage().toMap()));
			assertThat(child.parentRunId()).isEqualTo(parentRunId);
			assertThat(child.tags().get(BaseRunRecorder.TAG_TRACK)).isEqualTo("subagent");
			assertThat(parentToolCallIds).contains(spawn);
			assertThat(llm).isNotNull();
			assertThat(llm.totalCostUsd()).isZero();
			assertThat(llm.metadata().get("costAvailable")).isEqualTo(false);
			costSum += llmCost(ev);
			int[] t = tokens(ev);
			for (int k = 0; k < t.length; k++) {
				tokSum[k] += t[k];
			}
		}

		// (iv) accounting
		LLMCallEvent parentLlm = llm(parentEvents);
		assertThat(parentLlm).isNotNull();
		fact("(iv) parent llm_call tokens=" + parentLlm.tokenUsage().toMap() + " cost=" + parentLlm.totalCostUsd()
				+ " capture result-level input=" + capture.inputTokens() + " cacheCreation="
				+ capture.cacheCreationInputTokens() + " cacheRead=" + capture.cacheReadInputTokens() + " output="
				+ capture.outputTokens() + " totalCostUsd=" + capture.totalCostUsd());
		assertThat(parentLlm.tokenUsage().inputTokens()).isEqualTo(capture.inputTokens());
		assertThat(parentLlm.tokenUsage().cacheCreationTokens()).isEqualTo(capture.cacheCreationInputTokens());
		assertThat(parentLlm.tokenUsage().cacheReadTokens()).isEqualTo(capture.cacheReadInputTokens());
		fact("(iv) sum over all runs: llm_call cost=" + costSum + " input=" + tokSum[0] + " cacheCreation=" + tokSum[1]
				+ " cacheRead=" + tokSum[2] + " output=" + tokSum[3]);
		assertThat(costSum).isEqualTo(capture.totalCostUsd());
		fact("(iv) capture.modelCosts=" + capture.modelCosts());
		fact("(iv) parent llm meta=" + parentLlm.metadata());

		// (v) parent metadata about sub-agents
		fact("(v) subagentsWithoutTrack=" + parentLlm.metadata().get(BaseRunRecorder.META_SUBAGENTS_WITHOUT_TRACK)
				+ " subagentStats=" + parentLlm.metadata().get(BaseRunRecorder.META_SUBAGENT_STATS)
				+ " subagentTracksAvailable=" + parentLlm.metadata().get(BaseRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE)
				+ " costIncludesSubagents=" + parentLlm.metadata().get(BaseRunRecorder.META_COST_INCLUDES_SUBAGENTS));

		// (vi) where forwarded child text lands
		List<Path> durable = new ArrayList<>();
		try (Stream<Path> w = Files.walk(this.journalDir)) {
			w.filter(Files::isRegularFile).forEach(durable::add);
		}
		try (Stream<Path> w = Files.walk(traceDir)) {
			w.filter(Files::isRegularFile).forEach(durable::add);
		}
		for (SubagentCapture s : capture.subagents()) {
			String text = s.textOutput() == null ? "" : s.textOutput().strip();
			if (text.isEmpty()) {
				fact("(vi) subagent " + s.spawnToolUseId() + " textOutput is EMPTY");
				continue;
			}
			// Probe with plain words (no quotes or newlines) so JSON escaping cannot hide
			// a hit;
			// skip the echoed words, which also appear in tool results.
			List<String> probes = Stream.of(text.split("[^A-Za-z]+"))
				.filter(w -> w.length() >= 5 && !w.equalsIgnoreCase("alpha") && !w.equalsIgnoreCase("beta"))
				.distinct()
				.limit(3)
				.toList();
			fact("(vi) subagent " + s.spawnToolUseId() + " textOutput=" + text.replace("\n", "\\n") + " probes="
					+ probes);
			for (Path f : durable) {
				String content = Files.readString(f);
				boolean allWords = !probes.isEmpty() && probes.stream().allMatch(content::contains);
				long idLines = content.lines()
					.filter(l -> l.contains(s.spawnToolUseId()) || l.contains(s.agentId()))
					.count();
				fact("(vi)   " + (allWords ? "TEXT PRESENT in " : "text absent from ")
						+ this.workDir.getParent().relativize(f) + " (lines naming this subagent: " + idLines + ")");
			}
		}
		fact("(vi) durable files: "
				+ durable.stream().map(f -> this.workDir.getParent().relativize(f).toString()).toList());
	}

	private static LLMCallEvent llm(List<JournalEvent> events) {
		return events.stream()
			.filter(e -> e instanceof LLMCallEvent)
			.map(e -> (LLMCallEvent) e)
			.findFirst()
			.orElse(null);
	}

	private static double llmCost(List<JournalEvent> events) {
		return events.stream()
			.filter(e -> e instanceof LLMCallEvent)
			.mapToDouble(e -> ((LLMCallEvent) e).totalCostUsd())
			.sum();
	}

	private static int[] tokens(List<JournalEvent> events) {
		int[] t = new int[4];
		for (JournalEvent e : events) {
			if (e instanceof LLMCallEvent llm) {
				t[0] += llm.tokenUsage().inputTokens();
				t[1] += llm.tokenUsage().cacheCreationTokens();
				t[2] += llm.tokenUsage().cacheReadTokens();
				t[3] += llm.tokenUsage().outputTokens();
			}
		}
		return t;
	}

	private static void fact(String line) {
		System.out.println("FACT " + line);
	}

}
