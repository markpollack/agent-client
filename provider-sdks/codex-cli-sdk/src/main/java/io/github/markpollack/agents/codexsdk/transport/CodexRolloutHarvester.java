/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.codexsdk.types.CodexRollout;
import io.github.markpollack.agents.codexsdk.types.CodexRolloutHarvest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Locates and reads the persisted rollouts written by a Codex CLI run: the root thread's
 * file and, when the run spawned sub-agents, each child thread's file.
 * <p>
 * Codex writes one rollout per thread under {@code sessions/YYYY/MM/DD/}. The parent
 * names a child only through {@code event_msg/item_completed} records whose item is a
 * {@code SubAgentActivity} with {@code kind:"started"}; that item's
 * {@code agent_thread_id} is the suffix of the child's file name. Children may sit under
 * a different date directory than their parent, so lookup scans the whole sessions root.
 * This class owns discovery and copying of lines only; it does not interpret the records
 * beyond what discovery needs.
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

	/**
	 * Harvests the root rollout only. Kept for callers that need the flat line list;
	 * equal to {@code harvestRollouts(...).rootLines()} when a root is found.
	 */
	List<String> harvest(String sessionId, Path workingDirectory, Instant runStartedAt) {
		return awaitStable(() -> locate(sessionId, workingDirectory, runStartedAt)).map(this::readLines)
			.orElse(List.of());
	}

	/**
	 * Harvests the root rollout and every sub-agent rollout reachable from it. Each file,
	 * root or child, is read only once two consecutive polls observe the same size and
	 * modification time, or when the bounded wait expires with a candidate present. A
	 * child announced by its parent but absent from the store within the wait is listed
	 * in {@link CodexRolloutHarvest#missingThreadIds()} and the harvest continues.
	 */
	CodexRolloutHarvest harvestRollouts(String sessionId, Path workingDirectory, Instant runStartedAt) {
		Optional<Path> rootPath = awaitStable(() -> locate(sessionId, workingDirectory, runStartedAt));
		if (rootPath.isEmpty()) {
			return CodexRolloutHarvest.EMPTY;
		}

		List<CodexRollout> rollouts = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		Set<String> visited = new LinkedHashSet<>();
		Deque<CodexRollout> pending = new ArrayDeque<>();

		CodexRollout root = toRollout(rootPath.get(), sessionId);
		visited.add(root.threadId());
		rollouts.add(root);
		pending.add(root);

		while (!pending.isEmpty()) {
			CodexRollout parent = pending.poll();
			for (String childThreadId : startedSubAgentThreadIds(parent.lines())) {
				if (!visited.add(childThreadId)) {
					continue;
				}
				Optional<Path> childPath = awaitStable(() -> locateByThreadId(childThreadId));
				if (childPath.isEmpty()) {
					logger.warn("Codex sub-agent rollout {} announced by thread {} was not found under {}",
							childThreadId, parent.threadId(), sessionsRoot);
					missing.add(childThreadId);
					continue;
				}
				CodexRollout child = toRollout(childPath.get(), childThreadId);
				rollouts.add(child);
				pending.add(child);
			}
		}
		return new CodexRolloutHarvest(rollouts, missing);
	}

	Optional<Path> locate(String sessionId, Path workingDirectory, Instant runStartedAt) {
		if (!Files.isDirectory(sessionsRoot)) {
			return Optional.empty();
		}

		List<Path> rollouts = rolloutFiles();
		if (sessionId != null && !sessionId.isBlank()) {
			Optional<Path> bySession = byThreadId(rollouts, sessionId);
			if (bySession.isPresent()) {
				return bySession;
			}
		}

		if (workingDirectory == null) {
			return Optional.empty();
		}

		// Fallback by cwd and start time. Only root session metadata qualifies: a
		// sub-agent's session_meta carries parent_thread_id and an object-valued source,
		// and readSessionMetadata returns null for those, so a child is never chosen
		// here.
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

	Optional<Path> locateByThreadId(String threadId) {
		if (!Files.isDirectory(sessionsRoot) || threadId == null || threadId.isBlank()) {
			return Optional.empty();
		}
		return byThreadId(rolloutFiles(), threadId);
	}

	private Optional<Path> byThreadId(List<Path> rollouts, String threadId) {
		return rollouts.stream()
			.filter(path -> path.getFileName().toString().endsWith(threadId + ".jsonl"))
			.max(Comparator.comparing(this::lastModified));
	}

	/**
	 * Polls the locator until the same file is observed unchanged on two consecutive
	 * polls. If the deadline expires while a candidate is still changing, that candidate
	 * is returned so the caller gets the latest readable snapshot; a missing match stays
	 * empty.
	 */
	private Optional<Path> awaitStable(Supplier<Optional<Path>> locator) {
		Instant deadline = Instant.now().plus(wait);
		Path previousCandidate = null;
		FileSnapshot previousSnapshot = null;
		int stableObservations = 0;

		do {
			Optional<Path> candidate = locator.get();
			if (candidate.isPresent()) {
				Path path = candidate.get();
				FileSnapshot snapshot = snapshot(path);
				if (path.equals(previousCandidate) && snapshot != null && snapshot.equals(previousSnapshot)) {
					stableObservations++;
					if (stableObservations >= 2) {
						return Optional.of(path);
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

		return Optional.ofNullable(previousCandidate);
	}

	private CodexRollout toRollout(Path path, String fallbackThreadId) {
		List<String> lines = readLines(path);
		String threadId = fallbackThreadId;
		String parentThreadId = null;
		for (String line : lines) {
			JsonNode event = parse(line);
			if (event == null || !"session_meta".equals(event.path("type").asText())) {
				continue;
			}
			// Only the first session_meta identifies the thread: a forked child replays
			// the
			// parent's history, including the parent's session_meta, later in the file.
			JsonNode payload = event.path("payload");
			String id = payload.path("id").asText(null);
			if (id != null && !id.isBlank()) {
				threadId = id;
			}
			parentThreadId = payload.path("parent_thread_id").asText(null);
			break;
		}
		return new CodexRollout(threadId, parentThreadId, path.toAbsolutePath().toString(), lines);
	}

	static List<String> startedSubAgentThreadIds(List<String> lines) {
		Set<String> threadIds = new LinkedHashSet<>();
		for (String line : lines) {
			JsonNode event = parse(line);
			if (event == null || !"event_msg".equals(event.path("type").asText())) {
				continue;
			}
			JsonNode payload = event.path("payload");
			if (!"item_completed".equals(payload.path("type").asText())) {
				continue;
			}
			JsonNode item = payload.path("item");
			if (!"SubAgentActivity".equals(item.path("type").asText())
					|| !"started".equals(item.path("kind").asText())) {
				continue;
			}
			String threadId = item.path("agent_thread_id").asText(null);
			if (threadId != null && !threadId.isBlank()) {
				threadIds.add(threadId);
			}
		}
		return List.copyOf(threadIds);
	}

	private static JsonNode parse(String line) {
		try {
			return MAPPER.readTree(line);
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
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

	/**
	 * Reads the first {@code session_meta} of a rollout for the cwd-and-time fallback.
	 * Returns {@code null} when the file has no usable metadata or when the metadata
	 * belongs to a sub-agent thread ({@code parent_thread_id} present, or {@code source}
	 * not a plain string), because a child must never be chosen as the root.
	 */
	private SessionMetadata readSessionMetadata(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			String line;
			while ((line = reader.readLine()) != null) {
				JsonNode event = MAPPER.readTree(line);
				if (!"session_meta".equals(event.path("type").asText())) {
					continue;
				}
				JsonNode payload = event.path("payload");
				JsonNode parentThreadId = payload.path("parent_thread_id");
				if (!parentThreadId.isMissingNode() && !parentThreadId.isNull()) {
					return null;
				}
				JsonNode source = payload.path("source");
				if (!source.isMissingNode() && !source.isNull() && !source.isTextual()) {
					return null;
				}
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
