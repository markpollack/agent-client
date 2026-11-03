/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.claude.ClaudeAcquisition;

/**
 * Uses the owning qualifier; this adapter binds sources, never interprets vendor events.
 */
record NativeAssociation(Path root, String json) {
	static NativeAssociation prepare(Path output, ExecutionDecision decision, String revision, ClaudeAcquisition source)
			throws IOException {
		if (!source.complete())
			throw new IllegalArgumentException("Native acquisition is incomplete");
		if (!hash(source.manifest()).equals(source.sha256()))
			throw new IllegalArgumentException("Acquisition manifest changed");
		var qualified = io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition.qualify(source.manifest(),
				output);
		return qualified(output, decision, revision, null, qualified);
	}

	static NativeAssociation qualified(Path output, ExecutionDecision decision, String revision, String parent,
			io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition.Qualified qualified) throws IOException {
		var manifest = new ObjectMapper().readTree(Files.readAllBytes(qualified.manifest()));
		String nativeId = manifest.required("run").required("session_uid").asText();
		String connection = decision.connectionId().orElseThrow();
		var sources = new ArrayList<Map<String, Object>>();
		for (var item : manifest.required("sources")) {
			String role = item.required("role").asText();
			String stream = switch (role) {
				case "stdin" -> "STDIN";
				case "stream" -> "STDOUT";
				case "stderr" -> "STDERR";
				default -> null;
			};
			sources.add(occurrence(output, item.required("path").asText(), item.required("origin").asText(), stream,
					nativeId, connection));
		}
		sources.add(occurrence(output, "acquisition.json", "qualified-manifest", null, nativeId, connection));
		String association = DecisionEncoding.json(DecisionEncoding.object("codec", "journal-association/1",
				"executionId", decision.executionId(), "decisionId", decision.decisionId(), "revisionId", revision,
				"priorRevisionId", parent, "clientSessionId", decision.sessionId(), "connectionIds",
				List.of(connection), "providerObservation", DecisionEncoding.provider(decision.provider()),
				"acquisition", "COMPLETED", "continuation", null, "sources", sources, "missing", List.of()));
		return new NativeAssociation(output, association);
	}

	private static Map<String, Object> occurrence(Path base, String path, String origin, String stream, String nativeId,
			String connection) throws IOException {
		Path file = base.resolve(path);
		long size = Files.size(file);
		return DecisionEncoding.object("sourceId", path, "origin", origin, "relativePath", path, "sha256", hash(file),
				"size", size, "start", 0, "end", size, "stream", stream, "nativeSessionId", nativeId, "nativeTurnId",
				null, "nativeConnectionId", null, "clientConnectionId", connection);
	}

	private static String hash(Path path) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
