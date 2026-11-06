/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import io.github.markpollack.agents.client.ExecutionDecision;

/** Sealed original streams; completeness does not assert Journal route qualification. */
public record AntigravityAcquisition(String executionId, String connectionId, Optional<String> nativeId, Path directory,
		String expectedCliVersion, List<Source> sources, boolean complete, String detail) {

	/**
	 * The manifest format, used as the acquisition profile. No Journal qualifier exists
	 * for it.
	 */
	public static final String PROFILE = "antigravity-acquisition-v0.1";

	public AntigravityAcquisition {
		sources = List.copyOf(sources);
	}

	/**
	 * Bind an immutable original Client decision by identity; a different owner is
	 * refused.
	 */
	public Path writeManifest(ExecutionDecision decision) throws IOException {
		if (!this.executionId.equals(decision.executionId())
				|| !decision.connectionId().equals(Optional.of(this.connectionId)))
			throw new IllegalArgumentException("Decision does not own acquisition");
		var manifest = new LinkedHashMap<String, Object>();
		manifest.put("format", PROFILE);
		manifest.put("executionId", this.executionId);
		manifest.put("connectionId", this.connectionId);
		manifest.put("sessionId", decision.sessionId());
		manifest.put("decisionId", decision.decisionId());
		manifest.put("nativeId", this.nativeId.orElse("UNKNOWN"));
		manifest.put("expectedCliVersion", this.expectedCliVersion);
		manifest.put("nativeVersionObserved", false);
		manifest.put("complete", this.complete);
		manifest.put("detail", this.detail);
		manifest.put("sources", this.sources.stream()
			.map(s -> Map.of("role", s.role(), "path", s.path().toString(), "bytes", s.bytes(), "sha256", s.sha256(),
					"observedBytes", s.observedBytes(), "complete", s.complete(), "naturalEof", s.naturalEof()))
			.toList());
		byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
			.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
			.writeValueAsBytes(manifest);
		Path target = this.directory.resolve("antigravity-acquisition.json");
		try {
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
		}
		catch (FileAlreadyExistsException ex) {
			if (!Arrays.equals(bytes, Files.readAllBytes(target)))
				throw new IllegalArgumentException("Frozen decision changed", ex);
		}
		return target;
	}
	public record Source(String role, Path path, long bytes, String sha256, long observedBytes, boolean complete,
			boolean naturalEof) {
	}
}
