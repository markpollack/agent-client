/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds a stream of ACP {@code session/update} notifications into the handful of facts
 * worth keeping.
 *
 * <p>
 * Deliberately written against raw maps rather than the SDK's typed
 * {@code AcpSchema.SessionUpdate}. That is not laziness — it is the containment for a
 * measured defect. {@code acp-core} 0.16.1 types ten update kinds and throws
 * {@code InvalidTypeIdException} on any other, and <em>both</em> ACP agents measured for
 * this project emit {@code session_info_update}, which is not among the ten. Routed
 * through the SDK's own {@code sessionUpdateConsumer} that becomes an ERROR log and a
 * dropped update, per occurrence, for every agent at once. Folding from maps keeps an
 * SDK-version gap from being a shared failure mode: an unknown kind is counted in
 * {@link AcpRunRecord#unknownUpdateKinds()} and the run continues.
 *
 * <h2>Two rules the measurements forced</h2>
 *
 * <p>
 * <strong>Identity is {@code toolCallId}, not arrival.</strong> Grok 1.0.5 sends one
 * {@code tool_call} and one to two {@code tool_call_update}s per tool; Junie 26.8.24
 * spreads a step across many more. Folding by id is harmless when unnecessary and the
 * difference between a correct and a 3-to-5x inflated tool count when it is not.
 *
 * <p>
 * <strong>The structured field outranks the prose one, regardless of arrival
 * order.</strong> Grok's first update carries the machine name ({@code write}) as its
 * title and a later one replaces it with prose ({@code Write `/tmp/x/hello.txt`}); Junie
 * carries the two in the opposite order. Neither ordering can be relied upon, so the
 * first title is retained as the step's title and {@code kind} is tracked separately
 * rather than being overwritten by whichever update happened to land last.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public final class AcpUpdateFold {

	private final StringBuilder answer = new StringBuilder();

	private final StringBuilder thinking = new StringBuilder();

	private final Map<String, MutableStep> steps = new LinkedHashMap<>();

	private final Map<String, Integer> unknownKinds = new TreeMap<>();

	private int thoughtChunks;

	private int messageChunks;

	/**
	 * Accept one {@code session/update} payload.
	 *
	 * <p>
	 * Both the {@code params} map of the JSON-RPC notification and its inner
	 * {@code update} object are accepted, because vendor transports are not consistent
	 * about which they hand over.
	 * @param payload the notification params, or the update object itself
	 */
	@SuppressWarnings("unchecked")
	public void accept(Map<String, Object> payload) {
		if (payload == null) {
			return;
		}
		Map<String, Object> update = payload;
		Object nested = payload.get("update");
		if (nested instanceof Map<?, ?> map) {
			update = (Map<String, Object>) map;
		}

		String kind = str(update.get("sessionUpdate"));
		if (kind == null) {
			return;
		}
		switch (kind) {
			case "agent_message_chunk" -> {
				this.messageChunks++;
				this.answer.append(text(update.get("content")));
			}
			case "agent_thought_chunk" -> {
				this.thoughtChunks++;
				this.thinking.append(text(update.get("content")));
			}
			case "tool_call", "tool_call_update" -> foldToolCall(update);
			// Client-side affordances with no bearing on what the run did.
			case "user_message_chunk", "available_commands_update", "current_mode_update", "plan",
					"config_option_update", "usage_update" ->
				{
				}
			default -> this.unknownKinds.merge(kind, 1, Integer::sum);
		}
	}

	@SuppressWarnings("unchecked")
	private void foldToolCall(Map<String, Object> update) {
		String id = str(update.get("toolCallId"));
		if (id == null) {
			// An agent that omits the correlation id gives up folding for that call; a
			// synthetic id keeps it visible rather than silently merging it with another.
			id = "anonymous-" + this.steps.size();
		}
		MutableStep step = this.steps.computeIfAbsent(id, MutableStep::new);

		String title = str(update.get("title"));
		if (title != null && step.title == null) {
			step.title = title;
		}
		String toolKind = str(update.get("kind"));
		if (toolKind != null) {
			step.kind = toolKind;
		}
		String status = str(update.get("status"));
		if (status != null) {
			step.status = status;
		}
		if (update.get("rawInput") instanceof Map<?, ?> rawInput && step.rawInput == null) {
			step.rawInput = (Map<String, Object>) rawInput;
		}
		if (update.get("rawOutput") instanceof Map<?, ?> rawOutput) {
			step.rawOutput = (Map<String, Object>) rawOutput;
		}
		if (update.get("locations") instanceof List<?> locations) {
			for (Object location : locations) {
				if (location instanceof Map<?, ?> map) {
					String path = str(map.get("path"));
					if (path != null && !step.locations.contains(path)) {
						step.locations.add(path);
					}
				}
			}
		}
		if (update.get("_meta") instanceof Map<?, ?> meta && step.meta == null) {
			step.meta = (Map<String, Object>) meta;
		}
	}

	/**
	 * The folded tool calls, in the order their ids were first seen.
	 * @return one step per distinct {@code toolCallId}
	 */
	public List<AcpToolStep> toolSteps() {
		List<AcpToolStep> folded = new ArrayList<>(this.steps.size());
		for (MutableStep step : this.steps.values()) {
			folded.add(step.toRecord());
		}
		return List.copyOf(folded);
	}

	public String answer() {
		return this.answer.toString();
	}

	public String thinking() {
		return this.thinking.toString();
	}

	public int thoughtChunkCount() {
		return this.thoughtChunks;
	}

	public int messageChunkCount() {
		return this.messageChunks;
	}

	/**
	 * Update kinds this SDK version could not type, and how many arrived.
	 *
	 * <p>
	 * Published on the response rather than only logged, so that an agent outrunning the
	 * SDK is visible to the caller instead of being buried in a log file.
	 * @return kind to arrival count
	 */
	public Map<String, Integer> unknownUpdateKinds() {
		return Map.copyOf(this.unknownKinds);
	}

	/**
	 * The vendor {@code _meta} attached to a tool call, for a profile that knows how to
	 * read its own agent's extensions.
	 *
	 * <p>
	 * The ACP schema states clients must not assume any semantics for {@code _meta}, so
	 * this is exposed to per-agent code only and is never interpreted here.
	 * @param toolCallId the call to look up
	 * @return the raw meta map, or an empty map
	 */
	public Map<String, Object> toolMeta(String toolCallId) {
		MutableStep step = this.steps.get(toolCallId);
		return (step == null || step.meta == null) ? Map.of() : Map.copyOf(step.meta);
	}

	private static String text(Object content) {
		if (content instanceof Map<?, ?> map && "text".equals(str(map.get("type")))) {
			String value = str(map.get("text"));
			return (value != null) ? value : "";
		}
		return "";
	}

	private static String str(Object value) {
		return (value instanceof String string && !string.isBlank()) ? string : null;
	}

	private static final class MutableStep {

		private final String id;

		private final List<String> locations = new ArrayList<>();

		private String title;

		private String kind;

		private String status;

		private Map<String, Object> rawInput;

		private Map<String, Object> rawOutput;

		private Map<String, Object> meta;

		private MutableStep(String id) {
			this.id = id;
		}

		private AcpToolStep toRecord() {
			return new AcpToolStep(this.id, this.title, this.title, this.kind, this.status, this.locations,
					this.rawInput, this.rawOutput);
		}

	}

}
