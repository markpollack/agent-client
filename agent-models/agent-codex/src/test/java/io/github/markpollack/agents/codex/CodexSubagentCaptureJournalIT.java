/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.CodexRollout;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.codex.CodexPhaseCapture;
import io.github.markpollack.journal.codex.CodexRollouts;
import io.github.markpollack.journal.codex.CodexRunRecorder;
import io.github.markpollack.journal.codex.CodexSessionParser;
import io.github.markpollack.journal.codex.CodexSubagentCapture;
import io.github.markpollack.journal.codex.CodexToolUseRecord;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live proof: {@code codex exec} spawns two sub-agents; the harvested rollouts flow
 * through {@link CodexAgentModel#call} into agent-journal's {@link CodexRunRecorder} and
 * back out of a fresh {@link JsonFileStorage}. Prints {@code FACT} lines with structure
 * and numbers only.
 */
@Tag("live")
class CodexSubagentCaptureJournalIT {

	private static final String EXP = "codex-subagent-proof";

	private static final String PROMPT = "This is a capture test. Use your spawn_agent tool to start two sub-agents in "
			+ "sequence: the first must run the shell command 'echo alpha' and report what it printed; the second "
			+ "must run 'echo beta' and report what it printed. Wait for each to finish, then reply with the single "
			+ "word done.";

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path workDir;

	@TempDir
	Path journalDir;

	@AfterEach
	void tearDown() {
		Journal.reset();
	}

	private static void fact(String fmt, Object... args) {
		System.out.println("FACT " + String.format(fmt, args));
	}

	@Test
	void subagentCaptureRoundTripsThroughJournal() throws Exception {
		ExecuteOptions executeOptions = ExecuteOptions.builder()
			.dangerouslyBypassSandbox(true)
			.timeout(Duration.ofMinutes(6))
			.skipGitCheck(true)
			.build();
		CodexClient codexClient = CodexClient.create(executeOptions, workDir);
		String modelName = System.getProperty("codex.proof.model", "gpt-6.1-sol");
		CodexAgentOptions options = CodexAgentOptions.builder()
			.model(modelName)
			.timeout(Duration.ofMinutes(6))
			.dangerouslyBypassSandbox(true)
			.skipGitCheck(true)
			.build();
		CodexAgentModel model = new CodexAgentModel(codexClient, options, new LocalSandbox(workDir));
		assumeTrue(model.isAvailable(), "Codex CLI must be available");

		long t0 = System.nanoTime();
		AgentResponse response = model.call(AgentTaskRequest.builder(PROMPT, workDir).options(options).build());
		long callMs = (System.nanoTime() - t0) / 1_000_000;
		java.time.Instant returnedAt = java.time.Instant.now();
		Map<String, Object> fields = response.getMetadata().getProviderFields();
		fact("call.durationMs=%d exitCode=%s successful=%s model=%s", callMs, fields.get("exitCode"),
				fields.get("successful"), response.getMetadata().getModel());
		assertThat(fields).doesNotContainKey("error");
		assertThat(fields.get("exitCode")).as("codex exit code").isEqualTo(0);

		// ---- (i) rollouts
		@SuppressWarnings("unchecked")
		List<CodexRollout> rollouts = (List<CodexRollout>) fields.get("rollouts");
		@SuppressWarnings("unchecked")
		List<String> missing = (List<String>) fields.get("missingRolloutThreadIds");
		CodexRollout root = rollouts.get(0);
		fact("(i) rollouts.size=%d missingRolloutThreadIds=%d root.threadId=%s root.lines=%d", rollouts.size(),
				missing.size(), root.threadId(), root.lines().size());
		for (int n = 1; n < rollouts.size(); n++) {
			CodexRollout c = rollouts.get(n);
			fact("(i) child[%d].threadId=%s parentThreadId=%s parentIsRoot=%s lines=%d sourcePathUnderSessions=%s", n,
					c.threadId(), c.parentThreadId(), root.threadId().equals(c.parentThreadId()), c.lines().size(),
					c.sourcePath() != null && c.sourcePath().contains("/sessions/"));
			assertThat(c.parentThreadId()).isEqualTo(root.threadId());
		}
		for (CodexRollout r : rollouts) {
			java.time.Instant last = lastTimestamp(r.lines());
			fact("(timing) thread=%s lastRecordAt=%s callReturnedAt=%s lastRecordBeforeReturnMs=%d", r.threadId(), last,
					returnedAt, java.time.Duration.between(last, returnedAt).toMillis());
		}
		assertThat(rollouts.size()).as("rollout count").isEqualTo(3);
		assertThat(missing).isEmpty();

		// ---- (ii) capture
		CodexPhaseCapture capture = (CodexPhaseCapture) fields.get("phaseCapture");
		fact("(ii) sessionId=%s cliVersion=%s model=%s subagentTracksAvailable=%s subagents.size=%d",
				capture.sessionId(), capture.cliVersion(), capture.model(), capture.subagentTracksAvailable(),
				capture.subagents().size());
		assertThat(capture.sessionId()).isEqualTo(root.threadId());
		assertThat(capture.subagentTracksAvailable()).isTrue();
		assertThat(capture.subagents()).hasSize(2);
		for (CodexSubagentCapture s : capture.subagents()) {
			fact("(ii) subagent threadId=%s spawnCallId=%s parentSpawnCallId=%s depth=%d status=%s statusSource=%s "
					+ "forked=%s agentPath=%s toolUses=%d toolNames=%s in=%d cached=%d out=%d reasoning=%d "
					+ "replayedSkipped=%d durationMs=%d", s.threadId(), s.spawnCallId(), s.parentSpawnCallId(),
					s.depth(), s.status(), s.statusSource(), s.forked(), s.agentPath(), s.toolUses().size(),
					s.toolUses().stream().map(CodexToolUseRecord::name).toList(), s.inputTokens(),
					s.cachedInputTokens(), s.outputTokens(), s.reasoningOutputTokens(), s.replayedRecordsSkipped(),
					s.durationMs());
			assertThat(s.depth()).isEqualTo(1);
			assertThat(s.toolUses()).isNotEmpty();
		}

		// ---- (iii) parent tool uses
		List<String> parentNames = capture.toolUses().stream().map(CodexToolUseRecord::name).toList();
		Set<String> spawnIds = capture.toolUses()
			.stream()
			.filter(t -> CodexPhaseCapture.SPAWN_AGENT_TOOL.equals(t.name()))
			.map(CodexToolUseRecord::id)
			.collect(Collectors.toSet());
		fact("(iii) parent.toolUses=%d names=%s spawnAgentIds=%d spawnedThreadIds=%d", parentNames.size(), parentNames,
				spawnIds.size(), capture.spawnedThreadIds().size());
		assertThat(parentNames.stream().filter("spawn_agent"::equals).count()).isEqualTo(2);
		assertThat(parentNames).anyMatch(n -> n.startsWith("wait"));
		assertThat(parentNames).doesNotContain("exec", "shell", "exec_command");
		for (CodexSubagentCapture s : capture.subagents()) {
			assertThat(spawnIds).contains(s.spawnCallId());
		}
		assertThat(capture.subagents().stream().map(CodexSubagentCapture::spawnCallId).distinct()).hasSize(2);

		// ---- (iv) record and reload
		String keep = System.getProperty("codex.proof.journalDir");
		if (keep != null) {
			journalDir = Files.createDirectories(Path.of(keep).resolve("journal-" + root.threadId()));
		}
		JsonFileStorage storage = new JsonFileStorage(journalDir);
		Journal.configure(storage);
		long r0 = System.nanoTime();
		Run parent = Journal.run(EXP).start();
		new CodexRunRecorder(parent).recordPhase(capture);
		parent.finish(RunStatus.FINISHED);
		fact("(iv) recordMs=%d parentRunId=%s", (System.nanoTime() - r0) / 1_000_000, parent.id());

		JsonFileStorage fresh = new JsonFileStorage(journalDir);
		List<RunData> runs = fresh.listRuns(EXP);
		fact("(iv) runsOnDisk=%d expected=%d", runs.size(), 1 + capture.subagents().size());
		assertThat(runs).hasSize(1 + capture.subagents().size());
		RunData parentData = runs.stream().filter(r -> r.id().equals(parent.id())).findFirst().orElseThrow();
		List<JournalEvent> parentEvents = fresh.loadEvents(EXP, parent.id());
		Set<String> parentToolCallIds = parentEvents.stream()
			.filter(ToolCallEvent.class::isInstance)
			.map(e -> ((ToolCallEvent) e).id())
			.collect(Collectors.toSet());
		List<String> parentToolCallNames = parentEvents.stream()
			.filter(ToolCallEvent.class::isInstance)
			.map(e -> ((ToolCallEvent) e).toolName())
			.toList();
		fact("(iv) parent.events=%d parent.toolCalls=%s parent.status=%s", parentEvents.size(), parentToolCallNames,
				parentData.status());
		assertThat(parentToolCallNames).doesNotContain("exec", "shell", "exec_command");

		List<RunData> children = runs.stream().filter(r -> !r.id().equals(parent.id())).toList();
		for (RunData c : children) {
			List<JournalEvent> ev = fresh.loadEvents(EXP, c.id());
			List<String> toolNames = ev.stream()
				.filter(ToolCallEvent.class::isInstance)
				.map(e -> ((ToolCallEvent) e).toolName())
				.toList();
			Object spawnToolUseId = c.config().values().get(CodexRunRecorder.CONFIG_SPAWN_TOOL_USE_ID);
			fact("(iv) child runId=%s parentRunId=%s parentOk=%s track=%s spawnToolUseId=%s spawnIdInParentToolCalls=%s "
					+ "sessionId=%s sessionIsRoot=%s threadId=%s status=%s events=%d toolCalls=%s", c.id(),
					c.parentRunId(), parent.id().equals(c.parentRunId()), c.tags().toMap().get("track"), spawnToolUseId,
					parentToolCallIds.contains(spawnToolUseId), c.config().values().get("subagent.sessionId"),
					root.threadId().equals(c.config().values().get("subagent.sessionId")),
					c.config().values().get("subagent.threadId"), c.status(), ev.size(), toolNames);
			assertThat(c.parentRunId()).isEqualTo(parent.id());
			assertThat(c.tags().toMap()).containsEntry(CodexRunRecorder.TAG_TRACK, CodexRunRecorder.TRACK_SUBAGENT);
			assertThat(parentToolCallIds).contains((String) spawnToolUseId);
			assertThat(c.config().values()).containsEntry("subagent.sessionId", root.threadId());
			assertThat(toolNames).isNotEmpty();
		}

		// ---- (v) accounting
		LLMCallEvent parentCall = llmCall(parentEvents);
		JsonNode rootLast = lastTokenCount(root.lines(), "last_token_usage");
		JsonNode rootTotal = lastTokenCount(root.lines(), "total_token_usage");
		fact("(v) root.last_token_usage input=%d cached=%d output=%d reasoning=%d total=%d",
				rootLast.path("input_tokens").asInt(), rootLast.path("cached_input_tokens").asInt(),
				rootLast.path("output_tokens").asInt(), rootLast.path("reasoning_output_tokens").asInt(),
				rootLast.path("total_tokens").asInt());
		fact("(v) root.total_token_usage input=%d cached=%d output=%d reasoning=%d total=%d",
				rootTotal.path("input_tokens").asInt(), rootTotal.path("cached_input_tokens").asInt(),
				rootTotal.path("output_tokens").asInt(), rootTotal.path("reasoning_output_tokens").asInt(),
				rootTotal.path("total_tokens").asInt());
		fact("(v) parent.llm_call.tokenUsage input=%d output=%d thinking=%d cacheRead=%d costUsd=%s costAvailable=%s "
				+ "subagents=%s subagentTracksAvailable=%s subagentsWithoutTrack=%s",
				parentCall.tokenUsage().inputTokens(), parentCall.tokenUsage().outputTokens(),
				parentCall.tokenUsage().thinkingTokens(), parentCall.tokenUsage().cacheReadTokens(),
				parentCall.cost() == null ? "null" : parentCall.cost().totalUsd(),
				parentCall.metadata().get("costAvailable"), parentCall.metadata().get(CodexRunRecorder.META_SUBAGENTS),
				parentCall.metadata().get(CodexRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE),
				parentCall.metadata().get(CodexRunRecorder.META_SUBAGENTS_WITHOUT_TRACK));
		// The recorder reports the thread-cumulative total_token_usage of the last
		// token_count.
		assertThat(parentCall.tokenUsage().inputTokens())
			.isEqualTo(rootTotal.path("input_tokens").asInt() - rootTotal.path("cached_input_tokens").asInt());
		assertThat(parentCall.tokenUsage().outputTokens()).isEqualTo(rootTotal.path("output_tokens").asInt());
		assertThat(parentCall.tokenUsage().thinkingTokens())
			.isEqualTo(rootTotal.path("reasoning_output_tokens").asInt());
		assertThat(parentCall.tokenUsage().cacheReadTokens()).isEqualTo(rootTotal.path("cached_input_tokens").asInt());
		assertThat(parentCall.metadata()).containsEntry("costAvailable", false);
		assertThat(parentCall.cost() == null ? 0.0 : parentCall.cost().totalUsd()).isZero();

		long sumInputAllRuns = parentCall.tokenUsage().inputTokens() + parentCall.tokenUsage().cacheReadTokens();
		long sumOutputAllRuns = parentCall.tokenUsage().outputTokens();
		for (RunData c : children) {
			LLMCallEvent cc = llmCall(fresh.loadEvents(EXP, c.id()));
			String tid = (String) c.config().values().get("subagent.threadId");
			CodexRollout file = rollouts.stream().filter(r -> tid.equals(r.threadId())).findFirst().orElseThrow();
			JsonNode childLast = lastTokenCount(file.lines(), "total_token_usage");
			fact("(v) child %s llm_call input=%d output=%d thinking=%d cacheRead=%d costUsd=%s costAvailable=%s | "
					+ "file.total_token_usage input=%d cached=%d output=%d reasoning=%d", tid,
					cc.tokenUsage().inputTokens(), cc.tokenUsage().outputTokens(), cc.tokenUsage().thinkingTokens(),
					cc.tokenUsage().cacheReadTokens(), cc.cost() == null ? "null" : cc.cost().totalUsd(),
					cc.metadata().get("costAvailable"), childLast.path("input_tokens").asInt(),
					childLast.path("cached_input_tokens").asInt(), childLast.path("output_tokens").asInt(),
					childLast.path("reasoning_output_tokens").asInt());
			assertThat(cc.tokenUsage().inputTokens())
				.isEqualTo(childLast.path("input_tokens").asInt() - childLast.path("cached_input_tokens").asInt());
			assertThat(cc.tokenUsage().outputTokens()).isEqualTo(childLast.path("output_tokens").asInt());
			assertThat(cc.metadata()).containsEntry("costAvailable", false);
			assertThat(cc.cost() == null ? 0.0 : cc.cost().totalUsd()).isZero();
			sumInputAllRuns += cc.tokenUsage().inputTokens() + cc.tokenUsage().cacheReadTokens();
			sumOutputAllRuns += cc.tokenUsage().outputTokens();
		}
		fact("(v) sumAllRuns input(incl cached)=%d output=%d vs root.total_token_usage input=%d output=%d "
				+ "rootTotalExcludesChildren=%s", sumInputAllRuns, sumOutputAllRuns,
				rootTotal.path("input_tokens").asInt(), rootTotal.path("output_tokens").asInt(),
				sumInputAllRuns > rootTotal.path("input_tokens").asInt());

		// ---- (vi) statuses
		for (RunData c : children) {
			fact("(vi) child %s subagent.status=%s statusSource=%s statusMeaning=%s runStatus=%s",
					c.config().values().get("subagent.threadId"), c.summary().values().get("subagent.status"),
					c.summary().values().get("subagent.statusSource"),
					c.summary().values().get("subagent.statusMeaning"), c.status());
		}
		fact("(vi) subagentsWithoutTrack=%s", capture.subagentsWithoutTrack());
		assertThat(capture.subagentsWithoutTrack()).isEmpty();

		// ---- (vii) repeat
		Map<Path, byte[]> before = snapshot(journalDir);
		List<Path> firstFiles = new ArrayList<>(before.keySet());
		CodexRollouts again = new CodexRollouts(rollouts.stream()
			.map(r -> new io.github.markpollack.journal.codex.CodexRollout(r.threadId(), r.parentThreadId(),
					r.sourcePath(), r.lines()))
			.toList());
		CodexPhaseCapture capture2 = CodexSessionParser.parse(again, "phase-2", PROMPT);
		Run second = Journal.run(EXP).start();
		new CodexRunRecorder(second).recordPhase(capture2);
		second.finish(RunStatus.FINISHED);

		Map<Path, byte[]> after = snapshot(journalDir);
		List<Path> changed = firstFiles.stream()
			.filter(p -> !after.containsKey(p) || !Arrays.equals(before.get(p), after.get(p)))
			.toList();
		JsonFileStorage fresh2 = new JsonFileStorage(journalDir);
		List<RunData> all = fresh2.listRuns(EXP);
		List<RunData> secondChildren = all.stream().filter(r -> second.id().equals(r.parentRunId())).toList();
		Set<String> firstPairs = children.stream().map(CodexSubagentCaptureJournalIT::pair).collect(Collectors.toSet());
		Set<String> secondPairs = secondChildren.stream()
			.map(CodexSubagentCaptureJournalIT::pair)
			.collect(Collectors.toSet());
		fact("(vii) runsAfterRepeat=%d secondChildren=%d firstPairs=%s secondPairs=%s samePairs=%s "
				+ "firstRecordingFiles=%d changedOrMissingAfterRepeat=%s", all.size(), secondChildren.size(),
				firstPairs, secondPairs, firstPairs.equals(secondPairs), firstFiles.size(),
				changed.stream().map(p -> journalDir.relativize(p).toString()).toList());
		assertThat(all).hasSize(2 * (1 + capture.subagents().size()));
		assertThat(secondChildren).hasSize(capture.subagents().size());
		assertThat(secondPairs).isEqualTo(firstPairs);
		assertThat(changed).isEmpty();
	}

	private static String pair(RunData r) {
		return r.config().values().get("subagent.sessionId") + "|"
				+ r.config().values().get(CodexRunRecorder.CONFIG_SPAWN_TOOL_USE_ID);
	}

	private static LLMCallEvent llmCall(List<JournalEvent> events) {
		List<LLMCallEvent> calls = events.stream()
			.filter(LLMCallEvent.class::isInstance)
			.map(LLMCallEvent.class::cast)
			.toList();
		assertThat(calls).hasSize(1);
		return calls.get(0);
	}

	private static JsonNode lastTokenCount(List<String> lines, String field) throws IOException {
		JsonNode last = null;
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = JSON.readTree(line);
			JsonNode payload = node.path("payload");
			if ("event_msg".equals(node.path("type").asText()) && "token_count".equals(payload.path("type").asText())
					&& payload.path("info").hasNonNull(field)) {
				last = payload.path("info").path(field);
			}
		}
		assertThat(last).as("token_count." + field).isNotNull();
		return last;
	}

	private static java.time.Instant lastTimestamp(List<String> lines) throws IOException {
		java.time.Instant last = java.time.Instant.EPOCH;
		for (String line : lines) {
			if (!line.isBlank()) {
				JsonNode ts = JSON.readTree(line).path("timestamp");
				if (ts.isTextual()) {
					last = java.time.Instant.parse(ts.asText());
				}
			}
		}
		return last;
	}

	private static Map<Path, byte[]> snapshot(Path dir) throws IOException {
		Map<Path, byte[]> out = new LinkedHashMap<>();
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
				out.put(p, Files.readAllBytes(p));
			}
		}
		return out;
	}

}
