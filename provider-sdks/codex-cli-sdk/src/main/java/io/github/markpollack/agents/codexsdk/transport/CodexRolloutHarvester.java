/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Locates and reads the persisted rollout written by a Codex CLI run.
 */
final class CodexRolloutHarvester {

	private static final Logger logger = LoggerFactory.getLogger(CodexRolloutHarvester.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Duration START_TIME_TOLERANCE = Duration.ofSeconds(5);

	private final Path sessionsRoot;

	private final Duration wait;

	private final Duration pollInterval;

	CodexRolloutHarvester(Path sessionsRoot, Duration wait, Duration pollInterval) {
		this.sessionsRoot = sessionsRoot;
		this.wait = wait;
		this.pollInterval = pollInterval;
	}

	static Path defaultSessionsRoot() {
		String codexHome = System.getenv("CODEX_HOME");
		Path home = codexHome != null && !codexHome.isBlank() ? Path.of(codexHome)
				: Path.of(System.getProperty("user.home"), ".codex");
		return home.resolve("sessions");
	}

	List<String> harvest(String sessionId, Path workingDirectory, Instant runStartedAt) {
		Instant deadline = Instant.now().plus(wait);
		Path previousCandidate = null;
		FileSnapshot previousSnapshot = null;
		int stableObservations = 0;

		do {
			Optional<Path> candidate = locate(sessionId, workingDirectory, runStartedAt);
			if (candidate.isPresent()) {
				Path path = candidate.get();
				FileSnapshot snapshot = snapshot(path);
				if (path.equals(previousCandidate) && snapshot != null && snapshot.equals(previousSnapshot)) {
					stableObservations++;
					if (stableObservations >= 2) {
						return readLines(path);
					}
				}
				else {
					stableObservations = 0;
				}
				previousCandidate = path;
				previousSnapshot = snapshot;
			}

			if (!Instant.now().isBefore(deadline)) {
				break;
			}
		}
		while (pause());

		// If the deadline expired while a matching file was still changing, return the
		// latest readable snapshot. A missing match remains an honest empty trajectory.
		return previousCandidate != null ? readLines(previousCandidate) : List.of();
	}

	Optional<Path> locate(String sessionId, Path workingDirectory, Instant runStartedAt) {
		if (!Files.isDirectory(sessionsRoot)) {
			return Optional.empty();
		}

		List<Path> rollouts = rolloutFiles();
		if (sessionId != null && !sessionId.isBlank()) {
			Optional<Path> bySession = rollouts.stream()
				.filter(path -> path.getFileName().toString().endsWith(sessionId + ".jsonl"))
				.max(Comparator.comparing(this::lastModified));
			if (bySession.isPresent()) {
				return bySession;
			}
		}

		if (workingDirectory == null) {
			return Optional.empty();
		}

		Path normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
		Instant earliestSession = runStartedAt.minus(START_TIME_TOLERANCE);
		return rollouts.stream()
			.filter(path -> !lastModified(path).isBefore(earliestSession))
			.map(this::readSessionMetadata)
			.filter(Objects::nonNull)
			.filter(metadata -> metadata.cwd().equals(normalizedWorkingDirectory))
			.filter(metadata -> !metadata.timestamp().isBefore(earliestSession))
			.max(Comparator.comparing(SessionMetadata::timestamp)
				.thenComparing(metadata -> lastModified(metadata.path())))
			.map(SessionMetadata::path);
	}

	private List<Path> rolloutFiles() {
		try (Stream<Path> paths = Files.walk(sessionsRoot)) {
			return paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().startsWith("rollout-"))
				.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
				.toList();
		}
		catch (IOException ex) {
			logger.debug("Failed to scan Codex session store {}", sessionsRoot, ex);
			return List.of();
		}
	}

	private SessionMetadata readSessionMetadata(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			String line;
			while ((line = reader.readLine()) != null) {
				JsonNode event = MAPPER.readTree(line);
				if (!"session_meta".equals(event.path("type").asText())) {
					continue;
				}
				JsonNode payload = event.path("payload");
				String cwd = payload.path("cwd").asText(null);
				String timestamp = payload.path("timestamp").asText(null);
				if (cwd == null || timestamp == null) {
					return null;
				}
				return new SessionMetadata(path, Path.of(cwd).toAbsolutePath().normalize(), Instant.parse(timestamp));
			}
		}
		catch (IOException | RuntimeException ex) {
			logger.trace("Ignoring unreadable Codex rollout metadata in {}", path, ex);
		}
		return null;
	}

	private FileSnapshot snapshot(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
			return new FileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
		}
		catch (IOException ex) {
			return null;
		}
	}

	private Instant lastModified(Path path) {
		FileSnapshot snapshot = snapshot(path);
		return snapshot != null ? snapshot.modifiedAt() : Instant.MIN;
	}

	private List<String> readLines(Path path) {
		try {
			return List.copyOf(Files.readAllLines(path));
		}
		catch (IOException ex) {
			logger.debug("Failed to read Codex rollout {}", path, ex);
			return List.of();
		}
	}

	private boolean pause() {
		try {
			Thread.sleep(pollInterval.toMillis());
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private record SessionMetadata(Path path, Path cwd, Instant timestamp) {
	}

	private record FileSnapshot(long size, Instant modifiedAt) {
	}

}
