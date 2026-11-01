/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;

/**
 * Extracts only execution response facts from SDK types. Journal interprets the evidence.
 */
final class ClaudeExecutionResult {

	private final StringBuilder text = new StringBuilder();

	private ResultMessage result;

	private String sessionId;

	void accept(ParsedMessage parsed) {
		if (!parsed.isRegularMessage()) {
			return;
		}
		var message = parsed.asMessage();
		if (message instanceof AssistantMessage assistant) {
			assistant.getTextContent().ifPresent(text::append);
		}
		else if (message instanceof SystemMessage system && system.data() != null) {
			Object id = system.data().get("session_id");
			if (id instanceof String value) {
				observeSession(value);
			}
		}
		else if (message instanceof ResultMessage terminal) {
			observeSession(terminal.sessionId());
			result = terminal;
		}
	}

	private void observeSession(String id) {
		if (id == null || id.isBlank()) {
			return;
		}
		if (sessionId != null && !sessionId.equals(id)) {
			throw new IllegalStateException("Conflicting native session identities in one invocation");
		}
		sessionId = id;
	}

	static ClaudeExecutionResult read(Iterator<ParsedMessage> messages, Consumer<ParsedMessage> listener) {
		var response = new ClaudeExecutionResult();
		while (messages.hasNext()) {
			var parsed = messages.next();
			response.accept(parsed);
			if (listener != null) {
				listener.accept(parsed);
			}
		}
		return response;
	}

	String sessionId() {
		return sessionId;
	}

	boolean terminalReceived() {
		return result != null;
	}

	boolean successful() {
		return result != null && !result.isError();
	}

	AgentResponse response(String model, Duration duration, ClaudeAcquisition acquisition, String failure) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("terminalReceived", terminalReceived());
		if (acquisition != null) {
			fields.put("claudeAcquisition", acquisition);
		}
		if (failure != null) {
			fields.put("executionError", failure);
		}
		if (result != null) {
			fields.put("numTurns", result.numTurns());
			if (result.totalCostUsd() != null) {
				fields.put("totalCostUsd", result.totalCostUsd());
			}
			if (result.usage() != null) {
				fields.put("reportedUsage",
						java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(result.usage())));
			}
		}
		String finish = failure == null && successful() ? "SUCCESS" : "ERROR";
		var generation = new AgentGeneration(output(), new AgentGenerationMetadata(finish, Map.of()));
		var metadata = AgentResponseMetadata.builder()
			.model(model)
			.duration(duration)
			.sessionId(sessionId == null ? "" : sessionId)
			.providerFields(fields)
			.build();
		return new AgentResponse(List.of(generation), metadata);
	}

	private String output() {
		if (result == null) {
			return text.toString();
		}
		if (result.structuredOutput() != null) {
			try {
				return new ObjectMapper().writeValueAsString(result.structuredOutput());
			}
			catch (JsonProcessingException ex) {
				throw new IllegalStateException("Invalid structured output", ex);
			}
		}
		return result.result() == null ? text.toString() : result.result();
	}

}
