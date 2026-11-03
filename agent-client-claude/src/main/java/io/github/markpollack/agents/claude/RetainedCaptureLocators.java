/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.util.*;
import java.security.*;
import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver.Stream;

/** Candidate locators are observations, never an archive membership assertion. */
final class RetainedCaptureLocators {

	private RetainedCaptureLocators() {
	}

	static List<Map<String, Object>> describe(List<Map<String, Object>> turns, Map<Stream, byte[]> raw) {
		var result = new ArrayList<Map<String, Object>>();
		for (var turn : turns) {
			var value = new LinkedHashMap<>(turn);
			value.put("promptCandidates", locate(turn, raw.get(Stream.STDIN), "STDIN", "user"));
			value.put("resultCandidates", locate(turn, raw.get(Stream.STDOUT), "STDOUT", "result"));
			result.add(value);
		}
		return result;
	}

	private static List<Map<String, Object>> locate(Map<String, Object> turn, byte[] bytes, String stream,
			String type) {
		var result = new ArrayList<Map<String, Object>>();
		if (!(turn.get("end") instanceof Map<?, ?> end))
			return result;
		long from = ((Number) ((Map<?, ?>) turn.get("start")).get(stream)).longValue();
		long until = ((Number) end.get(stream)).longValue();
		int start = 0;
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] != '\n')
				continue;
			int stop = i + 1;
			if (start >= from && stop <= until) {
				try {
					var node = CaptureFiles.JSON.readTree(Arrays.copyOfRange(bytes, start, stop));
					if (node != null && type.equals(node.path("type").asText()))
						result.add(Map.of("stream", stream, "start", start, "end", stop, "sha256",
								HexFormat.of()
									.formatHex(MessageDigest.getInstance("SHA-256")
										.digest(Arrays.copyOfRange(bytes, start, stop)))));
				}
				catch (java.io.IOException | NoSuchAlgorithmException ex) {
					// Original bytes remain; malformed/unknown records gain no locator.
				}
			}
			start = stop;
		}
		return result;
	}

}
