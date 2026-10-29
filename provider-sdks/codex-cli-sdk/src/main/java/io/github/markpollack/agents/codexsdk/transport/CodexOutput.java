/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

/** Terminal text and model metadata extracted from combined Codex output. */
record CodexOutput(String message, String activityLog, String model) {

	static CodexOutput parse(String combinedOutput) {
		String[] lines = combinedOutput.split("\n");
		Markers markers = findMarkers(lines);
		int codexMarker = markers.codex();
		int tokensMarker = markers.tokens();
		String activityLog = combinedOutput;
		String finalMessage = "";
		if (codexMarker >= 0) {
			// Extract text between "codex" marker and "tokens used" (or end of
			// output)
			int end = tokensMarker > codexMarker ? tokensMarker : lines.length;
			if (codexMarker + 1 < end) {
				finalMessage = String.join("\n", java.util.Arrays.copyOfRange(lines, codexMarker + 1, end));
			}
		}
		else if (tokensMarker >= 0 && tokensMarker > 0) {
			// Fallback: no "codex" marker, take everything before "tokens used"
			finalMessage = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, tokensMarker));
		}
		else {
			// No markers found — treat entire output as the message
			finalMessage = combinedOutput;
		}

		// Extract model from activity log
		String model = extractModel(activityLog);
		return new CodexOutput(finalMessage, activityLog, model);
	}

	private static Markers findMarkers(String[] lines) {
		// Find the last "codex" marker and "tokens used" marker
		int codexMarker = -1;
		int tokensMarker = -1;
		for (int i = lines.length - 1; i >= 0; i--) {
			if (tokensMarker < 0 && lines[i].trim().equals("tokens used")) {
				tokensMarker = i;
			}
			if (codexMarker < 0 && lines[i].trim().equals("codex")) {
				codexMarker = i;
			}
			if (codexMarker >= 0 && tokensMarker >= 0) {
				break;
			}
		}
		return new Markers(codexMarker, tokensMarker);
	}

	/**
	 * Extracts model name from Codex stderr output. Model appears in stderr like: "model:
	 * gpt-5-codex"
	 * @param stderr the stderr output
	 * @return extracted model name or "codex-default" if not found
	 */
	private static String extractModel(String stderr) {
		if (stderr == null || stderr.isEmpty()) {
			return "codex-default";
		}

		// Look for "model: <model-name>" in stderr
		String[] lines = stderr.split("\n", -1);
		for (String line : lines) {
			if (line.startsWith("model:")) {
				return line.substring("model:".length()).trim();
			}
		}

		return "codex-default";
	}

	private record Markers(int codex, int tokens) {
	}

}
