/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents token usage metrics for a Gemini query. Provides analytics and calculations
 * for token consumption.
 */
public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
		@JsonProperty("completion_tokens") int completionTokens, @JsonProperty("total_tokens") int totalTokens) {

	@JsonCreator
	public Usage(@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("completion_tokens") int completionTokens, @JsonProperty("total_tokens") int totalTokens) {
		this.promptTokens = Math.max(0, promptTokens);
		this.completionTokens = Math.max(0, completionTokens);
		this.totalTokens = totalTokens > 0 ? totalTokens : (promptTokens + completionTokens);
	}

	public static Usage of(int promptTokens, int completionTokens) {
		return new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
	}

	public static Usage empty() {
		return new Usage(0, 0, 0);
	}

	/**
	 * Calculates the compression ratio (total tokens / prompt tokens).
	 */
	public double getCompressionRatio() {
		return promptTokens > 0 ? (double) totalTokens / promptTokens : 0.0;
	}

	/**
	 * Calculates the expansion ratio (completion tokens / prompt tokens).
	 */
	public double getExpansionRatio() {
		return promptTokens > 0 ? (double) completionTokens / promptTokens : 0.0;
	}

	/**
	 * Gets the percentage of tokens used for the prompt.
	 */
	public double getPromptPercentage() {
		return totalTokens > 0 ? (double) promptTokens / totalTokens * 100 : 0.0;
	}

	/**
	 * Gets the percentage of tokens used for the completion.
	 */
	public double getCompletionPercentage() {
		return totalTokens > 0 ? (double) completionTokens / totalTokens * 100 : 0.0;
	}

	/**
	 * Checks if this usage has any tokens.
	 */
	public boolean hasTokens() {
		return totalTokens > 0;
	}

	/**
	 * Adds another usage to this one.
	 */
	public Usage add(Usage other) {
		if (other == null)
			return this;
		return new Usage(this.promptTokens + other.promptTokens, this.completionTokens + other.completionTokens,
				this.totalTokens + other.totalTokens);
	}
}