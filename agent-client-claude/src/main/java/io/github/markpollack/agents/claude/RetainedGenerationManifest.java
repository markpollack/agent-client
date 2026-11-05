/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.agents.client.*;

/** Source claims for the owning Journal qualifier, not a second archive oracle. */
final class RetainedGenerationManifest {

	private final JsonNode capture;

	private final Path root;

	private final List<RetainedRecord> records = new ArrayList<>();

	private final Set<RetainedRecord> owned = new HashSet<>();

	private RetainedGenerationManifest(Path diagnostic) throws IOException {
		this.root = diagnostic.getParent();
		this.capture = CaptureFiles.JSON.readTree(diagnostic.toFile());
	}

	static Path write(Path diagnostic, List<ExecutionDecision> decisions) throws IOException {
		return new RetainedGenerationManifest(diagnostic).write(decisions);
	}

	private boolean fresh() {
		return this.capture.has("freshStart");
	}

	private Path write(List<ExecutionDecision> decisions) throws IOException {
		validate(decisions);
		var document = header(decisions.getFirst().sessionId());
		document.put("sources", sources());
		var turns = new ArrayList<Map<String, Object>>();
		for (var observed : this.capture.required("turns"))
			turns.add(turn(observed, decisions, turns.isEmpty() && fresh()));
		document.put("turns", turns);
		document.put("shared",
				this.records.stream().filter(RetainedRecord::shared).map(RetainedRecord::locator).toList());
		document.put("unassigned",
				this.records.stream()
					.filter(r -> !r.shared() && !this.owned.contains(r))
					.map(RetainedRecord::locator)
					.toList());
		if (fresh())
			freshMembers(document);
		Path target = this.root.resolve(fresh() ? "retained-fresh-generation.json" : "retained-generation.json");
		Files.writeString(target, CaptureFiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
				StandardOpenOption.CREATE_NEW);
		return target;
	}

	private void validate(List<ExecutionDecision> decisions) {
		if (decisions.size() != 2 || this.capture.required("turns").size() != 2)
			throw new IllegalArgumentException("Exactly two successful retained turns required");
		if (!this.capture.path("transportComplete").asBoolean())
			throw new IllegalArgumentException("Generation transport is incomplete");
		for (var decision : decisions)
			validateDecision(decision, decisions.getFirst().sessionId(),
					this.capture.required("connectionId").asText());
	}

	private static void validateDecision(ExecutionDecision decision, String session, String connection) {
		if (!(decision.outcome() instanceof ExecutionOutcome.Success) || !decision.sessionId().equals(session)
				|| !decision.connectionId().equals(Optional.of(connection)))
			throw new IllegalArgumentException("Wrong decision/session/generation or unsuccessful turn");
	}

	/**
	 * The ordered observation journal, launch and emitted identity are carried through
	 * unchanged.
	 */
	private void freshMembers(Map<String, Object> document) {
		for (String member : List.of("freshStart", "observedNativeIdentity", "observations")) {
			var value = this.capture.get(member);
			if (value == null || value.isNull())
				throw new IllegalArgumentException("Fresh start not fully observed: " + member);
			document.put(member, CaptureFiles.JSON.convertValue(value, Map.class));
		}
	}

	private Map<String, Object> header(String logicalSession) {
		var result = new LinkedHashMap<String, Object>();
		result.put("kind", fresh() ? "journal-retained-fresh-generation/1" : "journal-retained-generation/1");
		result.put("logicalSessionId", logicalSession);
		for (String field : List.of("connectionId", "nativeSessionId", "cliVersion", "captureStart"))
			result.put(field, this.capture.required(field).asText());
		for (String field : List.of("sealed", "observedResourcesTerminated", "stdoutNaturalEof", "stderrNaturalEof",
				"overflow"))
			result.put(field, this.capture.required(field).asBoolean());
		return result;
	}

	private List<Map<String, Object>> sources() throws IOException {
		var result = new ArrayList<Map<String, Object>>();
		for (var source : this.capture.required("sources")) {
			String stream = source.required("stream").asText();
			result.add(source(stream, source.required("path").asText(),
					"SDK-generation:" + this.capture.path("connectionId").asText() + ":" + stream,
					source.required("sha256").asText(), source.required("captured").asLong()));
		}
		var nativeFiles = this.capture.path("nativeFiles");
		if (nativeFiles.size() != 1 || !nativeFiles.get(0).path("state").asText().equals("acquired"))
			throw new IllegalArgumentException("Exact final native transcript unavailable");
		var nativeFile = nativeFiles.get(0);
		result.add(source("TRANSCRIPT", nativeFile.required("path").asText(), nativeFile.required("origin").asText(),
				nativeFile.required("sha256").asText(), nativeFile.required("bytes").asLong()));
		return result;
	}

	private Map<String, Object> source(String id, String relative, String origin, String expectedHash,
			long expectedSize) throws IOException {
		byte[] bytes = NativePrefixObservation.read(this.root.resolve(relative));
		if (bytes.length != expectedSize || !NativePrefixObservation.hash(bytes).equals(expectedHash))
			throw new IOException("Original retained source changed after capture: " + id);
		this.records.addAll(RetainedRecord.read(id, bytes));
		return Map.of("source", id, "path", relative, "origin", origin, "sha256", NativePrefixObservation.hash(bytes),
				"size", bytes.length, "observed", bytes.length, "dropped", 0);
	}

	private Map<String, Object> turn(JsonNode observed, List<ExecutionDecision> decisions, boolean firstFresh) {
		String execution = observed.required("executionId").asText();
		var matches = decisions.stream().filter(d -> d.executionId().equals(execution)).toList();
		if (matches.size() != 1)
			throw new IllegalArgumentException("Missing or repeated original decision for execution");
		var prompt = select("STDIN", observed.required("start").required("STDIN").asLong(),
				observed.required("end").required("STDIN").asLong());
		var stdout = select("STDOUT", observed.required("start").required("STDOUT").asLong(),
				observed.required("end").required("STDOUT").asLong());
		var before = firstFresh ? absentBefore(observed.required("nativePrefixBefore"))
				: prefix(observed.required("nativePrefixBefore"));
		var after = prefix(observed.required("nativePrefixAfter"));
		var nativeRecords = select("TRANSCRIPT",
				before.containsKey("bytes") ? ((Number) before.get("bytes")).longValue() : 0,
				((Number) after.get("bytes")).longValue());
		var value = new LinkedHashMap<String, Object>();
		value.put("executionId", execution);
		value.put("decisionId", matches.getFirst().decisionId());
		value.put("prompt", unique(prompt, "user").locator());
		value.put("result", unique(stdout, "result").locator());
		value.put("stdoutRecords", stdout.stream().map(RetainedRecord::locator).toList());
		value.put("nativeRecords", nativeRecords.stream().map(RetainedRecord::locator).toList());
		value.put("nativePrefixBefore", before);
		value.put("nativePrefixAfter", after);
		value.put("startOffsets", CaptureFiles.JSON.convertValue(observed.required("start"), Map.class));
		value.put("endOffsets", CaptureFiles.JSON.convertValue(observed.required("end"), Map.class));
		return value;
	}

	/**
	 * A fresh first turn starts from witnessed absence; anything else is the ordinary
	 * observed prefix.
	 */
	private static Map<String, Object> absentBefore(JsonNode prefix) {
		if ("ABSENT".equals(prefix.path("state").asText()))
			return FreshStartObserver.ABSENT_BEFORE_FIRST_DISPATCH;
		return prefix(prefix);
	}

	private static Map<String, Object> prefix(JsonNode prefix) {
		if (!prefix.has("bytes") || !prefix.has("sha256"))
			throw new IllegalArgumentException("Actual native boundary prefix unavailable");
		return Map.of("bytes", prefix.required("bytes").asLong(), "sha256", prefix.required("sha256").asText());
	}

	private List<RetainedRecord> select(String source, long start, long end) {
		var selected = this.records.stream()
			.filter(r -> r.source().equals(source) && r.start() >= start && r.end() <= end && !r.shared())
			.toList();
		this.owned.addAll(selected);
		return selected;
	}

	private static RetainedRecord unique(List<RetainedRecord> records, String type) {
		var selected = records.stream().filter(r -> r.type().equals(type)).toList();
		if (selected.size() != 1)
			throw new IllegalArgumentException("Missing/ambiguous " + type + " locator");
		return selected.getFirst();
	}

}
