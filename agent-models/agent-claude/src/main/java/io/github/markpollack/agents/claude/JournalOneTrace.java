/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import io.github.markpollack.journal.trace.TraceContentMode;
import io.github.markpollack.journal.trace.TraceRawMode;

/**
 * The only class that touches Journal 1.x types. It is loaded solely after
 * {@link LegacyTrace#available()} confirmed them, so a runtime without the optional
 * dependencies never links against them.
 */
final class JournalOneTrace {

	private JournalOneTrace() {
	}

	static Object contentMode(String name) {
		return TraceContentMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
	}

	/** Consumes the stream through the legacy parser and writes the derived trace. */
	static Object parse(Iterator<ParsedMessage> messages, String runId, String prompt, Path tracePath,
			Object contentMode, Integer maxTurns) {
		TraceContentMode mode = contentMode == null ? TraceContentMode.TRUNCATED : (TraceContentMode) contentMode;
		int turns = maxTurns != null ? maxTurns : SessionLogParser.UNKNOWN_MAX_TURNS;
		return SessionLogParser.parse(messages, runId, prompt, tracePath, mode, TraceRawMode.NONE, turns);
	}

	static Object parseSession(Iterator<ParsedMessage> messages, String prompt) {
		return SessionLogParser.parse(messages, "session-prompt", prompt);
	}

	/** The pre-acquisition provider fields callers read through AgentClientResponse. */
	static void legacyFields(AgentResponseMetadata metadata, Object value, Path tracePath, Path archived) {
		PhaseCapture capture = (PhaseCapture) value;
		metadata.put("phaseCapture", capture);
		metadata.put("inputTokens", capture.inputTokens());
		metadata.put("outputTokens", capture.outputTokens());
		metadata.put("thinkingTokens", capture.thinkingTokens());
		if (metadata.get("totalCostUsd") == null) {
			metadata.put("totalCostUsd", capture.totalCostUsd());
		}
		if (tracePath != null) {
			metadata.put("tracePath", tracePath.toAbsolutePath().toString());
		}
		if (archived != null) {
			metadata.put("transcriptPath", archived.toAbsolutePath().toString());
		}
	}

	static void sessionFields(AgentResponseMetadata metadata, Object value) {
		PhaseCapture capture = (PhaseCapture) value;
		metadata.put("phaseCapture", capture);
		metadata.put("inputTokens", capture.inputTokens());
		metadata.put("outputTokens", capture.outputTokens());
	}

}
