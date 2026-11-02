/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import io.github.markpollack.agents.client.*;

/** Producer encoding only; Journal owns validation, publication and interpretation. */
final class DecisionEncoding {

	private DecisionEncoding() {
	}

	static String decision(ExecutionDecision d) {
		var c = d.correlation();
		return json(object("codec", "client-decision/1", "decisionId", d.decisionId(), "executionId", d.executionId(),
				"sessionId", d.sessionId(), "connectionId", d.connectionId().orElse(null), "acceptedAt",
				instant(d.acceptedAt()), "outcome", outcome(d.outcome()), "provider", provider(d.provider()),
				"correlation",
				object("experimentId", c.experimentId().orElse(null), "experimentRunId",
						c.experimentRunId().orElse(null), "experimentInvocationId",
						c.experimentInvocationId().orElse(null), "workflowRunId", c.workflowRunId().orElse(null),
						"workflowInvocationId", c.workflowInvocationId().orElse(null), "workflowAttemptId",
						c.workflowAttemptId().orElse(null))));
	}

	static Map<String, Object> provider(ProviderObservation p) {
		return object("kind", p.kind().name(), "nativeReason", p.nativeReason().orElse(null), "observedExitCode",
				p.observedExitCode().isPresent() ? p.observedExitCode().getAsInt() : null);
	}

	private static Map<String, Object> outcome(ExecutionOutcome outcome) {
		if (outcome instanceof ExecutionOutcome.Success)
			return object("kind", "SUCCESS", "reason", null, "failure", null);
		if (outcome instanceof ExecutionOutcome.Stopped stopped)
			return object("kind", "STOP", "reason", reason(stopped.reason()), "failure", null);
		if (outcome instanceof ExecutionOutcome.Cancelled cancelled)
			return object("kind", "CANCELLED", "reason", reason(cancelled.reason()), "failure", null);
		var f = ((ExecutionOutcome.Failed) outcome).failure();
		return object("kind", "FAILED", "reason", null, "failure", object("code", f.code().wireName(), "exceptionClass",
				f.exceptionClass(), "message", f.message(), "causes", f.causes()));
	}

	private static Map<String, Object> reason(Reason r) {
		return object("code", r.code(), "message", r.message(), "actor", r.actor(), "time", instant(r.time()));
	}

	static String association(ExecutionDecision d, String revision, String parent) {
		return json(object("codec", "journal-association/1", "executionId", d.executionId(), "decisionId",
				d.decisionId(), "revisionId", revision, "priorRevisionId", parent, "clientSessionId", d.sessionId(),
				"connectionIds", d.connectionId().stream().toList(), "providerObservation", provider(d.provider()),
				"acquisition",
				d.provider().kind() == ProviderObservation.Kind.NOT_DISPATCHED ? "NOT_DISPATCHED" : "UNAVAILABLE",
				"continuation", null, "sources", List.of(), "missing", List.of(object("origin", "native", "reason",
						"Native acquisition is not qualified for the evidence-only route"))));
	}

	static Map<String, Object> object(Object... pairs) {
		var result = new TreeMap<String, Object>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}

	static String instant(Instant value) {
		int year = value.atOffset(ZoneOffset.UTC).getYear();
		if (year < 1 || year > 9999)
			throw new IllegalArgumentException("Instant outside codec range");
		return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC).format(value);
	}

	static String json(Object value) {
		if (value == null)
			return "null";
		if (value instanceof String text)
			return quote(text);
		if (value instanceof Number number)
			return number.toString();
		if (value instanceof Map<?, ?> map)
			return map.entrySet()
				.stream()
				.map(e -> quote((String) e.getKey()) + ":" + json(e.getValue()))
				.collect(java.util.stream.Collectors.joining(",", "{", "}"));
		if (value instanceof List<?> list)
			return list.stream()
				.map(DecisionEncoding::json)
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		throw new IllegalArgumentException("Unsupported codec value");
	}

	private static String quote(String value) {
		var result = new StringBuilder("\"");
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '"' || c == '\\')
				result.append('\\').append(c);
			else if (c < 32)
				result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
			else if (Character.isHighSurrogate(c)) {
				if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
					throw new IllegalArgumentException("Unpaired surrogate");
				result.append(c).append(value.charAt(++i));
			}
			else if (Character.isLowSurrogate(c))
				throw new IllegalArgumentException("Unpaired surrogate");
			else
				result.append(c);
		}
		return result.append('"').toString();
	}

}
