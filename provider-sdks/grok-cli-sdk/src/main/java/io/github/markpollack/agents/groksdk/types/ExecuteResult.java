/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.types;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Result of one Grok CLI execution, parsed from the headless JSON envelope.
 *
 * <p>
 * Unlike the Codex adapter, nothing here is scraped out of log text. Grok's
 * {@code --output-format json} returns a real object, verified against grok 1.0.5:
 *
 * <pre>{@code
 * {
 *   "text": "...", "stopReason": "end_turn",
 *   "sessionId": "01a020d9-...", "requestId": "5bd4e075-...",
 *   "thought": "...",
 *   "usage": { "input_tokens": 13115, "cache_read_input_tokens": 2944,
 *              "cache_creation_input_tokens": 0, "output_tokens": 27,
 *              "reasoning_tokens": 22, "total_tokens": 16086 },
 *   "num_turns": 1,
 *   "total_cost_usd": 0.00473688,
 *   "modelUsage": { "grok-4.6-build": { ... } }
 * }
 * }</pre>
 *
 * <p>
 * Note {@code total_cost_usd}: Grok is the only CLI in this family that reports a real
 * per-run cost, so a caller does not have to reconstruct one from a price table.
 *
 * <p>
 * A response that is not JSON is kept verbatim in {@link #getText()} rather than being
 * discarded — when the CLI degrades, the raw text is the evidence.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class ExecuteResult {

	private static final Logger logger = LoggerFactory.getLogger(ExecuteResult.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String text;

	private final String rawOutput;

	private final String sessionId;

	private final String stopReason;

	private final String model;

	private final int inputTokens;

	private final int outputTokens;

	private final int reasoningTokens;

	private final int cacheReadInputTokens;

	private final int cacheCreationInputTokens;

	private final int totalTokens;

	private final int numTurns;

	private final double totalCostUsd;

	private final int exitCode;

	private final Duration duration;

	private final boolean structured;

	private ExecuteResult(Builder builder) {
		this.text = builder.text;
		this.rawOutput = builder.rawOutput;
		this.sessionId = builder.sessionId;
		this.stopReason = builder.stopReason;
		this.model = builder.model;
		this.inputTokens = builder.inputTokens;
		this.outputTokens = builder.outputTokens;
		this.reasoningTokens = builder.reasoningTokens;
		this.cacheReadInputTokens = builder.cacheReadInputTokens;
		this.cacheCreationInputTokens = builder.cacheCreationInputTokens;
		this.totalTokens = builder.totalTokens;
		this.numTurns = builder.numTurns;
		this.totalCostUsd = builder.totalCostUsd;
		this.exitCode = builder.exitCode;
		this.duration = builder.duration;
		this.structured = builder.structured;
	}

	/**
	 * Parse the CLI's stdout. Falls back to treating the whole output as the response
	 * text when it is not the expected envelope.
	 * @param stdout raw stdout from the CLI
	 * @param exitCode process exit code
	 * @param duration wall-clock duration
	 * @return the parsed result
	 */
	public static ExecuteResult parse(String stdout, int exitCode, Duration duration) {
		Builder builder = new Builder().rawOutput(stdout).exitCode(exitCode).duration(duration);
		if (stdout == null || stdout.isBlank()) {
			return builder.text("").structured(false).build();
		}
		try {
			JsonNode root = MAPPER.readTree(stdout);
			if (!root.isObject() || !root.has("text")) {
				return builder.text(stdout).structured(false).build();
			}
			builder.text(root.path("text").asText(""))
				.sessionId(nullIfEmpty(root.path("sessionId").asText("")))
				.stopReason(nullIfEmpty(root.path("stopReason").asText("")))
				.numTurns(root.path("num_turns").asInt(0))
				.totalCostUsd(root.path("total_cost_usd").asDouble(0.0))
				.structured(true);

			JsonNode usage = root.path("usage");
			builder.inputTokens(usage.path("input_tokens").asInt(0))
				.outputTokens(usage.path("output_tokens").asInt(0))
				.reasoningTokens(usage.path("reasoning_tokens").asInt(0))
				.cacheReadInputTokens(usage.path("cache_read_input_tokens").asInt(0))
				.cacheCreationInputTokens(usage.path("cache_creation_input_tokens").asInt(0))
				.totalTokens(usage.path("total_tokens").asInt(0));

			// The envelope names no model directly; modelUsage is keyed by the model that
			// actually ran, which is the honest answer when a CLI resolves an alias.
			JsonNode modelUsage = root.path("modelUsage");
			if (modelUsage.isObject() && modelUsage.fieldNames().hasNext()) {
				builder.model(modelUsage.fieldNames().next());
			}
			return builder.build();
		}
		catch (Exception ex) {
			logger.debug("Grok output was not the expected JSON envelope: {}", ex.getMessage());
			return builder.text(stdout).structured(false).build();
		}
	}

	private static String nullIfEmpty(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	public String getText() {
		return this.text;
	}

	public String getRawOutput() {
		return this.rawOutput;
	}

	public String getSessionId() {
		return this.sessionId;
	}

	public String getStopReason() {
		return this.stopReason;
	}

	public String getModel() {
		return this.model;
	}

	public int getInputTokens() {
		return this.inputTokens;
	}

	public int getOutputTokens() {
		return this.outputTokens;
	}

	public int getReasoningTokens() {
		return this.reasoningTokens;
	}

	public int getCacheReadInputTokens() {
		return this.cacheReadInputTokens;
	}

	public int getCacheCreationInputTokens() {
		return this.cacheCreationInputTokens;
	}

	public int getTotalTokens() {
		return this.totalTokens;
	}

	public int getNumTurns() {
		return this.numTurns;
	}

	public double getTotalCostUsd() {
		return this.totalCostUsd;
	}

	public int getExitCode() {
		return this.exitCode;
	}

	public Duration getDuration() {
		return this.duration;
	}

	/** Whether the JSON envelope was present; false means the text is unparsed stdout. */
	public boolean isStructured() {
		return this.structured;
	}

	public boolean isSuccessful() {
		return this.exitCode == 0;
	}

	static class Builder {

		private String text = "";

		private String rawOutput = "";

		private String sessionId;

		private String stopReason;

		private String model;

		private int inputTokens;

		private int outputTokens;

		private int reasoningTokens;

		private int cacheReadInputTokens;

		private int cacheCreationInputTokens;

		private int totalTokens;

		private int numTurns;

		private double totalCostUsd;

		private int exitCode;

		private Duration duration = Duration.ZERO;

		private boolean structured;

		Builder text(String text) {
			this.text = text;
			return this;
		}

		Builder rawOutput(String rawOutput) {
			this.rawOutput = rawOutput;
			return this;
		}

		Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		Builder stopReason(String stopReason) {
			this.stopReason = stopReason;
			return this;
		}

		Builder model(String model) {
			this.model = model;
			return this;
		}

		Builder inputTokens(int inputTokens) {
			this.inputTokens = inputTokens;
			return this;
		}

		Builder outputTokens(int outputTokens) {
			this.outputTokens = outputTokens;
			return this;
		}

		Builder reasoningTokens(int reasoningTokens) {
			this.reasoningTokens = reasoningTokens;
			return this;
		}

		Builder cacheReadInputTokens(int cacheReadInputTokens) {
			this.cacheReadInputTokens = cacheReadInputTokens;
			return this;
		}

		Builder cacheCreationInputTokens(int cacheCreationInputTokens) {
			this.cacheCreationInputTokens = cacheCreationInputTokens;
			return this;
		}

		Builder totalTokens(int totalTokens) {
			this.totalTokens = totalTokens;
			return this;
		}

		Builder numTurns(int numTurns) {
			this.numTurns = numTurns;
			return this;
		}

		Builder totalCostUsd(double totalCostUsd) {
			this.totalCostUsd = totalCostUsd;
			return this;
		}

		Builder exitCode(int exitCode) {
			this.exitCode = exitCode;
			return this;
		}

		Builder duration(Duration duration) {
			this.duration = duration;
			return this;
		}

		Builder structured(boolean structured) {
			this.structured = structured;
			return this;
		}

		ExecuteResult build() {
			return new ExecuteResult(this);
		}

	}

}
