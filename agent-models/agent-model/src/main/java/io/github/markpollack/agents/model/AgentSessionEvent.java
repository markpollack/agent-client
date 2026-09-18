/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.model;

import java.util.Map;

/** Provider-neutral events from a conversation turn. */
public sealed interface AgentSessionEvent {

	/**
	 * @param text assistant message text
	 */
	record Text(String text) implements AgentSessionEvent {
	}

	/**
	 * @param id provider tool call identifier
	 * @param name tool name
	 * @param arguments tool input
	 */
	record ToolCall(String id, String name, Map<String, Object> arguments) implements AgentSessionEvent {
		public ToolCall {
			arguments = Map.copyOf(arguments);
		}
	}

	/**
	 * @param id matching tool call identifier
	 * @param content provider result content, a string or JSON-compatible value
	 * @param error whether the tool reported an error
	 */
	record ToolResult(String id, Object content, boolean error) implements AgentSessionEvent {
	}

	/**
	 * @param outcome terminal turn outcome
	 */
	record Terminal(Outcome outcome) implements AgentSessionEvent {
	}

	/** Terminal outcome, separate from session lifecycle. */
	enum Outcome {

		SUCCESS, ERROR, CANCELLED

	}

}
