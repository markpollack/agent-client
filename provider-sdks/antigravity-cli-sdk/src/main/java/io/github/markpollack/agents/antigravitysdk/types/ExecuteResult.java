/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.types;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Result of one Antigravity CLI execution, parsed from the print-mode JSON envelope.
 *
 * <p>
 * Verified against agy 1.1.13:
 *
 * <pre>{@code
 * {
 *   "conversation_id": "fc8e2caf-...", "status": "SUCCESS",
 *   "response": "OK\n", "duration_seconds": 8.957684329, "num_turns": 1,
 *   "usage": { "input_tokens": 14106, "output_tokens": 276,
 *              "thinking_tokens": 275, "cache_read_tokens": 0, "total_tokens": 14382 }
 * }
 * }</pre>
 *
 * <h2>Soft denial</h2>
 *
 * <p>
 * The headless permission default is to <em>soft-deny</em>: a tool call that needs
 * approval it cannot obtain is refused, the run continues, the process exits 0, and the
 * envelope still reports {@code "status": "SUCCESS"}. The only trace is a notice on
 * stderr naming the tool.
 *
 * <p>
 * That makes an exit code and a status field jointly insufficient to tell whether the
 * agent did its work, so stderr is captured separately and scanned, and
 * {@link #isSoftDenied()} reports what the envelope will not. A real capture of this
 * happening is preserved at
 * {@code ~/projects/agent-workflow-final-reviews/polyglot-identity-r2/antigravity} — a
 * review run whose {@code run_command} was denied and which reported SUCCESS regardless.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class ExecuteResult {

	/** Terminal status reported in the envelope. */
	public static final String STATUS_SUCCESS = "SUCCESS";

	private static final Logger logger = LoggerFactory.getLogger(ExecuteResult.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Markers that identify a refused tool call. Matched case-insensitively.
	 *
	 * <p>
	 * Scanned against the envelope's {@code error} field <em>and</em> stderr. The
	 * documentation says these notices go to stderr; agy 1.1.13 puts them in
	 * {@code error} and leaves stderr empty — a denied {@code pwd} produces
	 * {@code "permission check failed for command \"pwd\": user denied permission to run
	 * command"} in the envelope and nothing on the error stream. Both are read, so the
	 * detector survives whichever way a given build reports it.
	 */
	private static final List<String> DENIAL_MARKERS = List.of("requires approval", "permission denied",
			"denied permission", "denied permission to run", "permission check failed", "soft-denied",
			"not allowed by policy", "--dangerously-skip-permissions");

	private final String response;

	private final String rawOutput;

	private final String stderr;

	private final String conversationId;

	private final String status;

	private final String error;

	private final int inputTokens;

	private final int outputTokens;

	private final int thinkingTokens;

	private final int cacheReadTokens;

	private final int totalTokens;

	private final int numTurns;

	private final int exitCode;

	private final Duration duration;

	private final boolean structured;

	private final List<String> permissionNotices;

	private ExecuteResult(Builder builder) {
		this.response = builder.response;
		this.rawOutput = builder.rawOutput;
		this.stderr = builder.stderr;
		this.conversationId = builder.conversationId;
		this.status = builder.status;
		this.error = builder.error;
		this.inputTokens = builder.inputTokens;
		this.outputTokens = builder.outputTokens;
		this.thinkingTokens = builder.thinkingTokens;
		this.cacheReadTokens = builder.cacheReadTokens;
		this.totalTokens = builder.totalTokens;
		this.numTurns = builder.numTurns;
		this.exitCode = builder.exitCode;
		this.duration = builder.duration;
		this.structured = builder.structured;
		this.permissionNotices = List.copyOf(builder.permissionNotices);
	}

	/**
	 * Parse stdout and stderr from one run.
	 * @param stdout the response or JSON envelope
	 * @param stderr diagnostics, where permission notices appear
	 * @param exitCode process exit code
	 * @param duration wall-clock duration
	 * @return the parsed result
	 */
	public static ExecuteResult parse(String stdout, String stderr, int exitCode, Duration duration) {
		Builder builder = new Builder().rawOutput(stdout).stderr(stderr).exitCode(exitCode).duration(duration);

		if (stdout == null || stdout.isBlank()) {
			return builder.response("").structured(false).permissionNotices(scanForDenials(stderr)).build();
		}
		try {
			JsonNode root = MAPPER.readTree(stdout);
			if (!root.isObject() || !root.has("response")) {
				return builder.response(stdout).structured(false).build();
			}
			builder.response(root.path("response").asText(""))
				.conversationId(nullIfEmpty(root.path("conversation_id").asText("")))
				.status(root.path("status").asText(""))
				// Present only on a failed run, and the only place the reason appears —
				// stderr stays empty for a rejected invocation.
				.error(nullIfEmpty(root.path("error").asText("")))
				.numTurns(root.path("num_turns").asInt(0))
				.structured(true);

			JsonNode usage = root.path("usage");
			builder.inputTokens(usage.path("input_tokens").asInt(0))
				.outputTokens(usage.path("output_tokens").asInt(0))
				.thinkingTokens(usage.path("thinking_tokens").asInt(0))
				.cacheReadTokens(usage.path("cache_read_tokens").asInt(0))
				.totalTokens(usage.path("total_tokens").asInt(0));

			String envelopeError = root.path("error").asText("");
			builder.permissionNotices(scanForDenials(envelopeError + "\n" + ((stderr != null) ? stderr : "")));
			return builder.build();
		}
		catch (Exception ex) {
			logger.debug("Antigravity output was not the expected JSON envelope: {}", ex.getMessage());
			return builder.response(stdout).structured(false).permissionNotices(scanForDenials(stderr)).build();
		}
	}

	private static List<String> scanForDenials(String text) {
		List<String> notices = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return notices;
		}
		for (String line : text.split("\\R")) {
			String lowered = line.toLowerCase(Locale.ROOT);
			if (DENIAL_MARKERS.stream().anyMatch(lowered::contains)) {
				notices.add(line.trim());
			}
		}
		return notices;
	}

	private static String nullIfEmpty(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	public String getResponse() {
		return this.response;
	}

	public String getRawOutput() {
		return this.rawOutput;
	}

	public String getStderr() {
		return this.stderr;
	}

	public String getConversationId() {
		return this.conversationId;
	}

	public String getStatus() {
		return this.status;
	}

	/**
	 * Why the run failed, when the envelope says so. Null on a successful run.
	 * @return the CLI's error message, or null
	 */
	public String getError() {
		return this.error;
	}

	public int getInputTokens() {
		return this.inputTokens;
	}

	public int getOutputTokens() {
		return this.outputTokens;
	}

	public int getThinkingTokens() {
		return this.thinkingTokens;
	}

	public int getCacheReadTokens() {
		return this.cacheReadTokens;
	}

	public int getTotalTokens() {
		return this.totalTokens;
	}

	public int getNumTurns() {
		return this.numTurns;
	}

	public int getExitCode() {
		return this.exitCode;
	}

	public Duration getDuration() {
		return this.duration;
	}

	public boolean isStructured() {
		return this.structured;
	}

	/**
	 * Tool calls the CLI refused because it could not obtain approval. Non-empty on a run
	 * that will otherwise present itself as a success.
	 * @return the stderr notices, in order
	 */
	public List<String> getPermissionNotices() {
		return this.permissionNotices;
	}

	/**
	 * Whether the run completed with at least one refused tool call.
	 * @return true when the agent was prevented from doing part of its work
	 */
	public boolean isSoftDenied() {
		return !this.permissionNotices.isEmpty();
	}

	/** Whether the run returned any response text at all. */
	public boolean hasResponse() {
		return this.response != null && !this.response.isBlank();
	}

	/**
	 * Whether the envelope's own status field says the run succeeded. Reported verbatim,
	 * and see {@link #isSuccessful()} for why it is not the last word.
	 * @return true when the CLI reported SUCCESS
	 */
	public boolean isReportedSuccessful() {
		return STATUS_SUCCESS.equalsIgnoreCase(this.status);
	}

	/**
	 * Whether the run actually produced work.
	 *
	 * <p>
	 * Deliberately not {@code status == SUCCESS}. agy 1.1.13 returns
	 * {@code "status":"ERROR"} together with a complete, correct response and an
	 * unrelated internal complaint — {@code "search directory /workspace does not exist"}
	 * — so taking the status at face value would throw away good work. It also returns
	 * {@code "status":"ERROR"} with an empty response when a tool call was denied, so
	 * ignoring the status would hide a real failure.
	 *
	 * <p>
	 * What separates the two cases is whether there is a response and whether anything
	 * was refused. {@link #getStatus()} and {@link #getError()} stay available verbatim,
	 * so a caller that prefers the CLI's own verdict can still have it.
	 * @return true when the process exited cleanly, produced a response, and had no tool
	 * call refused
	 */
	public boolean isSuccessful() {
		return this.exitCode == 0 && hasResponse() && !isSoftDenied();
	}

	static class Builder {

		private String response = "";

		private String rawOutput = "";

		private String stderr = "";

		private String conversationId;

		private String status = "";

		private String error;

		private int inputTokens;

		private int outputTokens;

		private int thinkingTokens;

		private int cacheReadTokens;

		private int totalTokens;

		private int numTurns;

		private int exitCode;

		private Duration duration = Duration.ZERO;

		private boolean structured;

		private List<String> permissionNotices = List.of();

		Builder response(String response) {
			this.response = response;
			return this;
		}

		Builder rawOutput(String rawOutput) {
			this.rawOutput = rawOutput;
			return this;
		}

		Builder stderr(String stderr) {
			this.stderr = (stderr != null) ? stderr : "";
			return this;
		}

		Builder conversationId(String conversationId) {
			this.conversationId = conversationId;
			return this;
		}

		Builder status(String status) {
			this.status = status;
			return this;
		}

		Builder error(String error) {
			this.error = error;
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

		Builder thinkingTokens(int thinkingTokens) {
			this.thinkingTokens = thinkingTokens;
			return this;
		}

		Builder cacheReadTokens(int cacheReadTokens) {
			this.cacheReadTokens = cacheReadTokens;
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

		Builder permissionNotices(List<String> permissionNotices) {
			this.permissionNotices = permissionNotices;
			return this;
		}

		ExecuteResult build() {
			return new ExecuteResult(this);
		}

	}

}
