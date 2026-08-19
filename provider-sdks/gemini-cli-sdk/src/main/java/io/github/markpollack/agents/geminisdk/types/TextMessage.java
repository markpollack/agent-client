/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a text message from Gemini CLI. This is the primary message type for Gemini
 * responses.
 */
public record TextMessage(@JsonProperty("type") MessageType type,
		@JsonProperty("content") String content) implements Message {

	@JsonCreator
	public TextMessage(@JsonProperty("type") MessageType type, @JsonProperty("content") String content) {
		this.type = type != null ? type : MessageType.ASSISTANT;
		this.content = content != null ? content : "";
	}

	public static TextMessage user(String content) {
		return new TextMessage(MessageType.USER, content);
	}

	public static TextMessage assistant(String content) {
		return new TextMessage(MessageType.ASSISTANT, content);
	}

	public static TextMessage system(String content) {
		return new TextMessage(MessageType.SYSTEM, content);
	}

	public static TextMessage error(String content) {
		return new TextMessage(MessageType.ERROR, content);
	}

	@Override
	public MessageType getType() {
		return type;
	}

	@Override
	public String getContent() {
		return content;
	}
}