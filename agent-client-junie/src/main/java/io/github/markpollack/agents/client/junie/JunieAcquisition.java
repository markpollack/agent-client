/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.ExecutionDecision;

/**
 * One Junie execution's native session, copied and described as observed. It never
 * decides qualification: every check here only records a capture error, and Journal's
 * qualifier repeats its own checks on the original bytes.
 *
 * @param executionId the Client execution
 * @param connectionId the Client connection
 * @param directory the new directory holding the copies and the manifest
 * @param workingDirectory the {@code --project} directory
 * @param argv the launch command, with secret values redacted
 * @param version the Junie build number Junie reported, or null
 * @param acpSessionId the {@code session/new} id, or null
 * @param stopReason the ACP stop reason, or null
 * @param nativeSessionsDirectory where Junie writes session directories
 * @param nativeSessionsNew the session directories that appeared during the turn
 * @param freshSession whether the session directory was absent before the turn
 * @param terminated whether every observed process ended
 * @param nativeTaskId the taskId of the single TaskStartedEvent, or null
 * @param sources the copied native files
 * @param errors capture errors; empty when complete
 * @param captureStart when the turn took its snapshot, before launching Junie
 */
record JunieAcquisition(String executionId, String connectionId, Path directory, Path workingDirectory,
		List<String> argv, String version, String acpSessionId, String stopReason, Path nativeSessionsDirectory,
		List<String> nativeSessionsNew, boolean freshSession, boolean terminated, String nativeTaskId,
		List<Source> sources, List<String> errors, java.time.Instant captureStart) {

	static final String SUMMARY = "summary.json";

	static final String EVENTS = "events.jsonl";

	private static final ObjectMapper JSON = new ObjectMapper();

	JunieAcquisition {
		argv = List.copyOf(argv);
		nativeSessionsNew = List.copyOf(nativeSessionsNew);
		sources = List.copyOf(sources);
		errors = List.copyOf(errors);
	}

	/**
	 * One copied native file. {@code observed} is the origin's length after the copy, so
	 * a file still growing shows as dropped bytes.
	 */
	record Source(String role, String artifact, String profile, Path origin, String state, long bytes,
			long observed, String sha256, String framing, long records) {

		boolean acquired() {
			return "acquired".equals(this.state);
		}

	}

	boolean complete() {
		return this.errors.isEmpty();
	}

	String detail() {
		return complete() ? "" : String.join("; ", this.errors);
	}

	String profile() {
		return JunieManifest.profile(this.version);
	}

	static JunieAcquisition seal(Path root, String executionId, String connectionId, Path workingDirectory,
			List<String> argv, JunieObservation observed) throws IOException {
		Files.createDirectories(root);
		Path directory = Files.createTempDirectory(root, "junie-");
		var errors = new ArrayList<String>();
		String session = observed.acpSessionId();
		String version = JunieObservation.buildNumber(observed.agentVersion());
		var fresh = observed.newSessions();
		boolean freshSession = session != null && !observed.preexisting(session);
		processErrors(observed, version, fresh, freshSession, errors);
		var sources = new ArrayList<Source>();
		String taskId = null;
		if (session != null) {
			Path nativeDirectory = observed.sessionsDirectory().resolve(session);
			Source summary = copy(nativeDirectory, directory, "summary", SUMMARY, "junie_summary", errors);
			Source events = copy(nativeDirectory, directory, "session", EVENTS, "junie_session_events", errors);
			sources.add(summary);
			sources.add(events);
			if (summary.acquired())
				checkSummary(directory.resolve(SUMMARY), session, workingDirectory, errors);
			if (events.acquired())
				taskId = checkEvents(directory.resolve(EVENTS), errors);
		}
		return new JunieAcquisition(executionId, connectionId, directory, workingDirectory, argv, version, session,
				observed.stopReason(), observed.sessionsDirectory(), fresh, freshSession, observed.terminated(), taskId,
				sources, errors, observed.start);
	}

	/** The turn's own facts: version, identity, freshness, stop reason and cessation. */
	private static void processErrors(JunieObservation observed, String version, List<String> fresh,
			boolean freshSession, List<String> errors) {
		String session = observed.acpSessionId();
		if (version == null)
			errors.add("Junie reported no CLI version");
		if (session == null) {
			errors.add("No ACP session id was observed");
		}
		else {
			if (!fresh.equals(List.of(session)))
				errors.add("New native session directories " + fresh + ", not exactly [" + session + "]");
			if (!freshSession)
				errors.add("Native session directory " + session + " existed before the turn");
		}
		if (!"end_turn".equals(observed.stopReason()))
			errors.add("ACP stop reason " + observed.stopReason() + ", not end_turn");
		if (!observed.terminated())
			errors.add("The Junie processes were not observed to end");
	}

	private static Source copy(Path nativeDirectory, Path directory, String role, String artifact, String profile,
			List<String> errors) throws IOException {
		Path origin = nativeDirectory.resolve(artifact);
		boolean lines = EVENTS.equals(artifact);
		String framing = lines ? "lines" : "document";
		if (!Files.isRegularFile(origin)) {
			errors.add("Native " + artifact + " is missing");
			return new Source(role, artifact, profile, origin, "missing", 0, 0, null, framing, 0);
		}
		byte[] bytes = Files.readAllBytes(origin);
		long observed = Math.max(bytes.length, Files.size(origin));
		var credentials = lines ? credentialVariables(bytes) : java.util.Set.<String>of();
		if (!credentials.isEmpty()) {
			errors.add("Native " + artifact + " records credential-shaped environment variables " + credentials
					+ "; it was not copied");
			return new Source(role, artifact, profile, origin, "withheld", 0, observed, null, framing, 0);
		}
		Files.write(directory.resolve(artifact), bytes, StandardOpenOption.CREATE_NEW);
		if (observed != bytes.length)
			errors.add("Native " + artifact + " changed while it was copied");
		long records = lines ? lines(bytes) : 1;
		return new Source(role, artifact, profile, origin, "acquired", bytes.length, observed, sha256(bytes), framing,
				records);
	}

	private static void checkSummary(Path copy, String session, Path workingDirectory, List<String> errors) {
		try {
			JsonNode summary = JSON.readTree(copy.toFile());
			String id = summary.path("sessionId").asText(null);
			if (!session.equals(id))
				errors.add("summary.json sessionId " + id + " differs from ACP session " + session);
			String project = summary.path("projectDir").asText(null);
			if (project == null || !Path.of(project).toAbsolutePath().normalize().equals(workingDirectory))
				errors.add("summary.json projectDir " + project + " differs from working directory " + workingDirectory);
		}
		catch (IOException | RuntimeException ex) {
			errors.add("summary.json is not one JSON document: " + ex.getMessage());
		}
	}

	/**
	 * The names, never the values, of credential-shaped variables in Junie's recorded
	 * environment events. A line that is not JSON is left to the completeness check.
	 */
	static java.util.Set<String> credentialVariables(byte[] bytes) {
		var names = new java.util.TreeSet<String>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
			if (!line.contains("EnvironmentVariablesUpdatedEvent"))
				continue;
			try {
				for (JsonNode variable : JSON.readTree(line).path("event").path("agentEvent").path("env")) {
					String name = variable.path("key").asText("");
					if (JunieLaunchProfile.credentialShaped(name))
						names.add(name);
				}
			}
			catch (IOException ex) {
				// not one JSON object: checkEvents reports it
			}
		}
		return names;
	}

	/** The taskId of the single TaskStartedEvent, or null with the reason recorded. */
	private static String checkEvents(Path copy, List<String> errors) throws IOException {
		byte[] bytes = Files.readAllBytes(copy);
		if (bytes.length == 0 || bytes[bytes.length - 1] != '\n')
			errors.add("events.jsonl does not end with a complete line");
		var tasks = new ArrayList<String>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
			if (line.isEmpty())
				continue;
			try {
				JsonNode event = JSON.readTree(line);
				if ("TaskStartedEvent".equals(event.path("kind").asText()))
					tasks.add(event.path("taskId").asText(null));
			}
			catch (IOException ex) {
				errors.add("events.jsonl has a line that is not JSON");
				return null;
			}
		}
		if (tasks.size() != 1 || tasks.get(0) == null) {
			errors.add("events.jsonl has " + tasks.size() + " TaskStartedEvent records, not exactly one");
			return null;
		}
		return tasks.get(0);
	}

	/** Bind the accepted decision by identity; a different owner is refused. */
	Path writeManifest(ExecutionDecision decision) throws IOException {
		if (!this.executionId.equals(decision.executionId())
				|| !decision.connectionId().equals(Optional.of(this.connectionId)))
			throw new IllegalArgumentException("Decision does not own this Junie acquisition");
		byte[] bytes = JunieManifest.write(this, decision);
		Path target = this.directory.resolve("acquisition.json");
		try {
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
		}
		catch (FileAlreadyExistsException ex) {
			if (!Arrays.equals(bytes, Files.readAllBytes(target)))
				throw new IllegalArgumentException("Frozen decision changed", ex);
		}
		return target;
	}

	private static long lines(byte[] bytes) {
		long count = 0;
		for (byte value : bytes)
			if (value == '\n')
				count++;
		if (bytes.length > 0 && bytes[bytes.length - 1] != '\n')
			count++;
		return count;
	}

	static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
