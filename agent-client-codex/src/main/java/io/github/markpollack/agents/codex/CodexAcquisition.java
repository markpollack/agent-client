/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Original sources with explicit completeness; native interpretation belongs to Journal.
 */
public record CodexAcquisition(String executionId, String connectionId, Optional<String> nativeId, Path directory,
		List<Source> sources, boolean complete, String detail, String manifestTemplate) {

	public CodexAcquisition {
		sources = List.copyOf(sources);
	}

	/**
	 * Freeze owning decision/session attribution without changing observed provider
	 * facts.
	 */
	public Path writeManifest(io.github.markpollack.agents.client.ExecutionDecision decision)
			throws java.io.IOException {
		if (!this.executionId.equals(decision.executionId())
				|| !decision.connectionId().equals(Optional.of(this.connectionId)))
			throw new IllegalArgumentException("Decision does not own this acquisition");
		var json = new com.fasterxml.jackson.databind.ObjectMapper();
		var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(this.manifestTemplate);
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.required("process")).put("client_session_uid",
				decision.sessionId());
		((com.fasterxml.jackson.databind.node.ObjectNode) manifest.required("turns").get(0)).put("decision_uid",
				decision.decisionId());
		byte[] bytes = json.writeValueAsBytes(manifest);
		Path file = this.directory.resolve("codex-acquisition.json");
		try {
			java.nio.file.Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
		}
		catch (java.nio.file.FileAlreadyExistsException ex) {
			if (!java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(file)))
				throw new IllegalArgumentException("Frozen acquisition decision changed", ex);
		}
		return file;
	}

	/**
	 * Digest/extent of the sealed bytes, separately from total observed bytes and EOF.
	 */
	public record Source(String role, Path path, long bytes, String sha256, long observedBytes, boolean complete,
			boolean naturalEof) {
	}
}
