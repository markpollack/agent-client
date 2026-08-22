/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

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

}
