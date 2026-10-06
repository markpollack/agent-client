/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import io.github.markpollack.agents.codexsdk.types.CodexRollout;
import io.github.markpollack.agents.codexsdk.types.CodexRolloutHarvest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class CodexRolloutHarvesterTest {

	private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

	@TempDir
	Path tempDir;

	@Test
	void sessionIdWinsOverNewerRolloutFromSameWorkingDirectory() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		String expectedSession = "0199b2f0-e92a-76b3-88fa-a0fa925ad545";
		Path expected = writeRollout("rollout-old-" + expectedSession + ".jsonl", expectedSession, workingDirectory,
				startedAt, "expected");
		writeRollout("rollout-new-00000000-0000-0000-0000-000000000000.jsonl", "00000000-0000-0000-0000-000000000000",
				workingDirectory, startedAt.plusMillis(10), "other");

		CodexRolloutHarvester harvester = harvester(Duration.ofMillis(100));

		assertThat(harvester.locate(expectedSession, workingDirectory, startedAt)).contains(expected);
		assertThat(harvester.harvest(expectedSession, workingDirectory, startedAt)).last().isEqualTo("expected");
	}

	@Test
	void fallbackSelectsNewestMatchingWorkingDirectoryRatherThanNewestOverall() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		writeRollout("rollout-matching-old.jsonl", "one", workingDirectory, startedAt, "old");
		Path expected = writeRollout("rollout-matching-new.jsonl", "two", workingDirectory, startedAt.plusMillis(10),
				"expected");
		writeRollout("rollout-newest-overall.jsonl", "three", tempDir.resolve("other"), startedAt.plusMillis(20),
				"wrong");

		CodexRolloutHarvester harvester = harvester(Duration.ofMillis(100));

		assertThat(harvester.locate(null, workingDirectory, startedAt)).contains(expected);
	}

	@Test
	void waitsForDelayedRolloutAndReturnsRawLines() {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		String sessionId = "0199b2f0-e92a-76b3-88fa-a0fa925ad545";
		CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(60);
				writeRollout("rollout-delayed-" + sessionId + ".jsonl", sessionId, workingDirectory, startedAt,
						"raw-event");
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});

		List<String> lines = harvester(Duration.ofSeconds(1)).harvest(sessionId, workingDirectory, startedAt);
		writer.join();

		assertThat(lines).hasSize(2);
		assertThat(lines.get(1)).isEqualTo("raw-event");
	}

	@Test
	void returnsEmptyWhenBoundedWaitExpiresWithoutMatch() {
		List<String> lines = harvester(Duration.ofMillis(80)).harvest("missing", tempDir.resolve("work"),
				Instant.now());

		assertThat(lines).isEmpty();
	}

	@Test
	void harvestsRootAndChildrenAcrossDateDirectoriesRecursively() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		Path rootPath = writeRootRollout("2026/10/06", ROOT, workingDirectory, startedAt, "call-1", CHILD_A, "call-2",
				CHILD_B);
		Path childAPath = writeChildRollout("2026/10/06", CHILD_A, ROOT, workingDirectory, startedAt.plusSeconds(1),
				"call-3", GRANDCHILD);
		Path childBPath = writeChildRollout("2026/10/07", CHILD_B, ROOT, workingDirectory, startedAt.plusSeconds(2));
		Path grandchildPath = writeChildRollout("2026/10/07", GRANDCHILD, CHILD_A, workingDirectory,
				startedAt.plusSeconds(3));

		CodexRolloutHarvest harvest = harvester(Duration.ofMillis(300)).harvestRollouts(ROOT, workingDirectory,
				startedAt);

		assertThat(harvest.missingThreadIds()).isEmpty();
		assertThat(harvest.rollouts()).extracting(CodexRollout::threadId)
			.containsExactly(ROOT, CHILD_A, CHILD_B, GRANDCHILD);
		assertThat(harvest.rollouts()).extracting(CodexRollout::parentThreadId)
			.containsExactly(null, ROOT, ROOT, CHILD_A);
		assertThat(harvest.rollouts()).extracting(CodexRollout::sourcePath)
			.containsExactly(rootPath.toAbsolutePath().toString(), childAPath.toAbsolutePath().toString(),
					childBPath.toAbsolutePath().toString(), grandchildPath.toAbsolutePath().toString());
		assertThat(harvest.rollouts().get(0).lines()).isEqualTo(Files.readAllLines(rootPath));
		assertThat(harvest.rollouts().get(3).lines()).isEqualTo(Files.readAllLines(grandchildPath));
		assertThat(harvest.rootLines()).isEqualTo(Files.readAllLines(rootPath));
	}

	@Test
	void missingChildIsReportedAndDoesNotStopTheHarvest() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		Path rootPath = writeRootRollout("2026/10/06", ROOT, workingDirectory, startedAt, "call-1", CHILD_A, "call-2",
				CHILD_B);
		writeChildRollout("2026/10/06", CHILD_B, ROOT, workingDirectory, startedAt.plusSeconds(2));

		CodexRolloutHarvest harvest = harvester(Duration.ofMillis(100)).harvestRollouts(ROOT, workingDirectory,
				startedAt);

		assertThat(harvest.rollouts()).extracting(CodexRollout::threadId).containsExactly(ROOT, CHILD_B);
		assertThat(harvest.missingThreadIds()).containsExactly(CHILD_A);
		assertThat(harvest.rootLines()).isEqualTo(Files.readAllLines(rootPath));
	}

	@Test
	void fallbackNeverSelectsAChildEvenWhenItIsNewest() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		Path rootPath = writeRootRollout("2026/10/06", ROOT, workingDirectory, startedAt, "call-1", CHILD_A);
		writeChildRollout("2026/10/06", CHILD_A, ROOT, workingDirectory, startedAt.plusSeconds(5));
		// A rollout whose only marker is an object-valued source is a child too.
		Path objectSource = tempDir.resolve("sessions/2026/10/06/rollout-x-" + CHILD_B + ".jsonl");
		Files.write(objectSource,
				List.of("{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + CHILD_B + "\",\"cwd\":\""
						+ workingDirectory.toAbsolutePath().normalize() + "\",\"timestamp\":\""
						+ startedAt.plusSeconds(6) + "\",\"source\":{\"subagent\":{\"thread_spawn\":{}}}}}"));

		CodexRolloutHarvester harvester = harvester(Duration.ofMillis(100));

		assertThat(harvester.locate(null, workingDirectory, startedAt)).contains(rootPath);
		assertThat(harvester.harvestRollouts(null, workingDirectory, startedAt).rollouts())
			.extracting(CodexRollout::threadId)
			.containsExactly(ROOT, CHILD_A);
	}

	@Test
	void flatHarvestStillReturnsOnlyTheRootLines() throws Exception {
		Instant startedAt = Instant.now();
		Path workingDirectory = tempDir.resolve("work");
		Path rootPath = writeRootRollout("2026/10/06", ROOT, workingDirectory, startedAt, "call-1", CHILD_A);
		writeChildRollout("2026/10/06", CHILD_A, ROOT, workingDirectory, startedAt.plusSeconds(1));

		assertThat(harvester(Duration.ofMillis(100)).harvest(ROOT, workingDirectory, startedAt))
			.isEqualTo(Files.readAllLines(rootPath));
	}

	private static final String ROOT = "019a0000-0000-7000-8000-000000000001";

	private static final String CHILD_A = "019a0000-0000-7000-8000-00000000000a";

	private static final String CHILD_B = "019a0000-0000-7000-8000-00000000000b";

	private static final String GRANDCHILD = "019a0000-0000-7000-8000-0000000000aa";

	private CodexRolloutHarvester harvester(Duration wait) {
		return new CodexRolloutHarvester(tempDir.resolve("sessions"), wait, POLL_INTERVAL);
	}

	private Path writeRollout(String fileName, String sessionId, Path cwd, Instant timestamp, String event)
			throws Exception {
		Path directory = tempDir.resolve("sessions/2026/08/22");
		Files.createDirectories(directory);
		Path rollout = directory.resolve(fileName);
		String metadata = "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + sessionId + "\",\"cwd\":\""
				+ cwd.toAbsolutePath().normalize() + "\",\"timestamp\":\"" + timestamp + "\"}}";
		Files.write(rollout, List.of(metadata, event));
		return rollout;
	}

	/**
	 * Writes a root rollout ({@code source: "exec"}, no parent) that spawns the given
	 * children. {@code spawns} alternates spawn call id and child thread id.
	 */
	private Path writeRootRollout(String dateDirectory, String threadId, Path cwd, Instant timestamp, String... spawns)
			throws Exception {
		String meta = "{\"timestamp\":\"" + timestamp + "\",\"type\":\"session_meta\",\"payload\":{\"id\":\"" + threadId
				+ "\",\"timestamp\":\"" + timestamp + "\",\"cwd\":\"" + cwd.toAbsolutePath().normalize()
				+ "\",\"source\":\"exec\",\"cli_version\":\"0.160.1\"}}";
		return writeThread(dateDirectory, threadId, timestamp, meta, spawns);
	}

	/**
	 * Writes a sub-agent rollout: {@code session_id} is the root's id, {@code id} is the
	 * child's own thread id, {@code parent_thread_id} names the spawning thread and
	 * {@code source} is an object.
	 */
	private Path writeChildRollout(String dateDirectory, String threadId, String parentThreadId, Path cwd,
			Instant timestamp, String... spawns) throws Exception {
		String meta = "{\"timestamp\":\"" + timestamp + "\",\"type\":\"session_meta\",\"payload\":{\"id\":\"" + threadId
				+ "\",\"session_id\":\"" + ROOT + "\",\"parent_thread_id\":\"" + parentThreadId + "\",\"timestamp\":\""
				+ timestamp + "\",\"cwd\":\"" + cwd.toAbsolutePath().normalize()
				+ "\",\"source\":{\"subagent\":{\"thread_spawn\":{\"parent_thread_id\":\"" + parentThreadId
				+ "\",\"depth\":1}}},\"cli_version\":\"0.160.1\"}}";
		return writeThread(dateDirectory, threadId, timestamp, meta, spawns);
	}

	private Path writeThread(String dateDirectory, String threadId, Instant timestamp, String sessionMeta,
			String... spawns) throws Exception {
		Path directory = tempDir.resolve("sessions").resolve(dateDirectory);
		Files.createDirectories(directory);
		Path rollout = directory
			.resolve("rollout-" + timestamp.toString().replace(':', '-') + "-" + threadId + ".jsonl");
		List<String> lines = new java.util.ArrayList<>();
		lines.add(sessionMeta);
		lines.add("{\"timestamp\":\"" + timestamp + "\",\"type\":\"response_item\",\"payload\":{\"type\":\"message\","
				+ "\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"prompt for " + threadId
				+ "\"}]}}");
		for (int i = 0; i + 1 < spawns.length; i += 2) {
			String callId = spawns[i];
			String childThreadId = spawns[i + 1];
			lines.add("{\"timestamp\":\"" + timestamp
					+ "\",\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\","
					+ "\"name\":\"spawn_agent\",\"arguments\":\"{}\",\"call_id\":\"" + callId + "\"}}");
			lines.add("{\"timestamp\":\"" + timestamp
					+ "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\","
					+ "\"item\":{\"type\":\"SubAgentActivity\",\"id\":\"" + callId + "\",\"kind\":\"started\","
					+ "\"agent_thread_id\":\"" + childThreadId + "\"}}}");
			lines.add("{\"timestamp\":\"" + timestamp
					+ "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\","
					+ "\"item\":{\"type\":\"SubAgentActivity\",\"id\":\"" + callId + "\",\"kind\":\"completed\","
					+ "\"agent_thread_id\":\"" + childThreadId + "\"}}}");
		}
		lines.add("{\"timestamp\":\"" + timestamp + "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\","
				+ "\"last_agent_message\":\"done " + threadId + "\"}}");
		Files.write(rollout, lines);
		return rollout;
	}

}
