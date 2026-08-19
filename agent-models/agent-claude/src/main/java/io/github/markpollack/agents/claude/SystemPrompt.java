/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for system prompt configuration. Supports both string prompts and preset
 * prompts.
 *
 * @author Mark Pollack
 * @since 1.1.0
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = SystemPrompt.StringPrompt.class, name = "string"),
		@JsonSubTypes.Type(value = SystemPrompt.PresetPrompt.class, name = "preset") })
public sealed interface SystemPrompt permits SystemPrompt.StringPrompt, SystemPrompt.PresetPrompt {

	/**
	 * Simple string-based system prompt.
	 *
	 * @param prompt the system prompt text
	 */
	record StringPrompt(String prompt) implements SystemPrompt {
	}

	/**
	 * Preset-based system prompt configuration.
	 *
	 * @param preset the preset name (e.g., "claude_code")
	 * @param append optional text to append to the preset prompt
	 */
	record PresetPrompt(String preset, String append) implements SystemPrompt {

		/**
		 * Creates a preset prompt without additional text.
		 * @param preset the preset name
		 */
		public PresetPrompt(String preset) {
			this(preset, null);
		}

		/**
		 * Claude Code preset constant.
		 */
		public static final String CLAUDE_CODE = "claude_code";

		/**
		 * Creates a Claude Code preset prompt.
		 * @return a preset prompt for Claude Code
		 */
		public static PresetPrompt claudeCode() {
			return new PresetPrompt(CLAUDE_CODE);
		}

		/**
		 * Creates a Claude Code preset prompt with additional text.
		 * @param append text to append to the Claude Code preset
		 * @return a preset prompt for Claude Code with appended text
		 */
		public static PresetPrompt claudeCode(String append) {
			return new PresetPrompt(CLAUDE_CODE, append);
		}

	}

	/**
	 * Creates a simple string-based system prompt.
	 * @param prompt the prompt text
	 * @return a StringPrompt instance
	 */
	static StringPrompt of(String prompt) {
		return new StringPrompt(prompt);
	}

	/**
	 * Creates a preset-based system prompt.
	 * @param preset the preset name
	 * @return a PresetPrompt instance
	 */
	static PresetPrompt preset(String preset) {
		return new PresetPrompt(preset);
	}

	/**
	 * Creates a preset-based system prompt with additional text.
	 * @param preset the preset name
	 * @param append text to append
	 * @return a PresetPrompt instance
	 */
	static PresetPrompt preset(String preset, String append) {
		return new PresetPrompt(preset, append);
	}

}
