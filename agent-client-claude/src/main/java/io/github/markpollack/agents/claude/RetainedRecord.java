/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Original framing and locator claims; Journal independently verifies membership. */
record RetainedRecord(String source, int start, int end, JsonNode value, String sha256) {

	static List<RetainedRecord> read(String source, byte[] bytes) {
		var records = new ArrayList<RetainedRecord>();
		int start = 0;
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == '\n' || i == bytes.length - 1) {
				int end = i + 1;
				byte[] raw = Arrays.copyOfRange(bytes, start, end);
				records.add(new RetainedRecord(source, start, end, parse(raw), NativePrefixObservation.hash(raw)));
				start = end;
			}
		}
		return records;
	}

	private static JsonNode parse(byte[] bytes) {
		try {
			return Optional.ofNullable(CaptureFiles.JSON.readTree(bytes)).orElse(MissingNode.getInstance());
		}
		catch (java.io.IOException ex) {
			return MissingNode.getInstance();
		}
	}

	String type() {
		return this.value.path("type").asText();
	}

	boolean shared() {
		if (!Set.of("STDIN", "STDOUT").contains(this.source))
			return false;
		return Set.of("control_request", "control_response").contains(type()) || (this.source.equals("STDOUT")
				&& type().equals("system") && this.value.path("subtype").asText().equals("init"));
	}

	Map<String, Object> locator() {
		return Map.of("source", this.source, "start", this.start, "end", this.end, "sha256", this.sha256);
	}

}
