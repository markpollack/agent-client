/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.acp.AcpAgentProfile;
import io.github.markpollack.agents.acp.AcpRunRecord;
import io.github.markpollack.agents.acp.AcpToolStep;
import io.github.markpollack.agents.acp.AcpTrajectoryLocator;
import io.github.markpollack.agents.acp.AcpTrajectoryLocators;
import io.github.markpollack.agents.model.AgentOptions;

import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import io.github.markpollack.journal.grok.GrokToolUseRecord;

/**
 * Grok over ACP: the second implementation the generic {@code AcpAgentModel} was
 * extracted from.
 *
 * <h2>Why Grok, when the plan named Gemini</h2>
 *
 * <p>
 * The intent was to convert an existing provider rather than add one, so that the ACP
 * path and the native path could be compared on the <em>same</em> agent instead of across
 * two. Grok satisfies that better than Gemini does — {@code agent-grok} and
 * {@code grok-cli-capture} both already exist, and unlike Gemini it can actually be run:
 * Gemini CLI 0.54.4 completes ACP {@code initialize} and then refuses {@code session/new}
 * outright, because Google has withdrawn the {@code oauth-personal} tier this CLI
 * authenticates with. Gemini remains a protocol-level data point and its trajectory
 * scheme is implemented in {@link AcpTrajectoryLocators}; it is not a measured one.
 *
 * <h2>What ACP costs, measured on this agent</h2>
 *
 * <p>
 * The same task run through {@code grok agent stdio} and through
 * {@code grok --output-format streaming-json} produces the same tool calls, the same
 * text, and the same durable session directory — Grok writes {@code updates.jsonl},
 * {@code events.jsonl} and {@code chat_history.jsonl} identically either way, so the
 * control plane does not perturb the research record. Cost survives too, relocated rather
 * than lost: the native path reports it on a terminal {@code end} line, and the ACP path
 * returns the identical vector on the prompt response {@code _meta}, which
 * {@code acp-core} 0.16.1 does model. That is what makes {@link #capture} possible
 * without a trajectory parser.
 *
 * <p>
 * What the live protocol does <em>not</em> carry is in the durable {@code events.jsonl}
 * alone: per-tool {@code duration_ms}, time-to-first-token, and
 * {@code permission_requested}/{@code permission_resolved} with the decision and the
 * milliseconds a human took to make it. Neither plane subsumes the other, which is why
 * both are kept.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class GrokAcpProfile implements AcpAgentProfile {

	/**
	 * Grok reports cost as integer ticks. Measured against a run reporting both:
	 * {@code total_cost_usd_ticks} 57,466,800 alongside {@code total_cost_usd}
	 * 0.00574668, so one tick is 1e-10 USD.
	 */
	private static final BigDecimal TICKS_PER_USD = BigDecimal.valueOf(10_000_000_000L);

	private static final String VENDOR_TOOL_META = "x.ai/tool";

	private final Path sessionsDirectory;

	public GrokAcpProfile() {
		this(defaultSessionsDirectory());
	}

	public GrokAcpProfile(Path sessionsDirectory) {
		this.sessionsDirectory = (sessionsDirectory != null) ? sessionsDirectory : defaultSessionsDirectory();
	}

	/**
	 * Matches {@code Provider.GROK} in the parity TCK. Spelled as a literal so that a
	 * test-scope compatibility kit does not become a runtime dependency of the adapter.
	 */
	@Override
	public String providerKey() {
		return "GROK";
	}

	@Override
	public String defaultCommand() {
		return "grok";
	}

	/**
	 * {@code grok agent stdio}, with options placed on the {@code agent} subcommand.
	 *
	 * <p>
	 * Flag position matters here and is not guessable: {@code --always-approve} is an
	 * option of {@code grok agent}, and passing it after {@code stdio} exits 2 before a
	 * single byte of protocol is exchanged.
	 */
	@Override
	public List<String> launchArgs(Path workingDirectory, AgentOptions options) {
		List<String> args = new ArrayList<>(List.of("agent"));
		if (options != null) {
			if (options.getModel() != null) {
				args.add("--model");
				args.add(options.getModel());
			}
			if (options.getEffort() != null) {
				args.add("--reasoning-effort");
				args.add(options.getEffort());
			}
			if (options.isAutoApprove()) {
				args.add("--always-approve");
			}
		}
		args.add("stdio");
		return args;
	}

	/**
	 * {@code ~/.grok/sessions/<url-encoded cwd>/<sessionId>/updates.jsonl}.
	 *
	 * <p>
	 * The working directory in that key is the one passed to {@code session/new}, not the
	 * launched process's own — verified by running the two deliberately apart, which also
	 * matters because {@code AgentParameters} offers no way to set the child's working
	 * directory at all.
	 */
	@Override
	public AcpTrajectoryLocator trajectoryLocator() {
		return AcpTrajectoryLocators.byWorkingDirectoryAndSessionId(this.sessionsDirectory, "updates.jsonl")
			.existingOnly();
	}

	/**
	 * Grok's concrete tool name, from its own {@code _meta} extension.
	 *
	 * <p>
	 * ACP's {@code title} is a display string that Grok rewrites mid-call — {@code write}
	 * becomes {@code Write `/path/to/file`} on the following update — so it is unusable
	 * as an identity. The stable name is in {@code _meta["x.ai/tool"].name}, and reading
	 * it here rather than in the shared model is the point: the schema forbids clients
	 * from assuming any semantics for {@code _meta}, which makes it per-agent knowledge
	 * by definition.
	 */
	@Override
	public String toolName(AcpToolStep step, Map<String, Object> meta) {
		if (meta.get(VENDOR_TOOL_META) instanceof Map<?, ?> tool && tool.get("name") instanceof String name
				&& !name.isBlank()) {
			return name;
		}
		return step.title();
	}

	/**
	 * Grok's token and cost vector, taken from the prompt response {@code _meta}.
	 */
	@Override
	public Map<String, Object> providerFields(AcpRunRecord run) {
		Map<String, Object> usage = usage(run);
		if (usage.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("inputTokens", intValue(usage.get("inputTokens")));
		fields.put("outputTokens", intValue(usage.get("outputTokens")));
		fields.put("reasoningTokens", intValue(usage.get("reasoningTokens")));
		fields.put("cachedReadTokens", intValue(usage.get("cachedReadTokens")));
		fields.put("numTurns", intValue(usage.get("numTurns")));
		fields.put("costUsd", costUsd(usage));
		// Grok reconciles: the ticks here reproduce the native path's total_cost_usd for
		// the same task, so this is reported cost rather than a pricing-table estimate.
		fields.put("costSource", "reported");
		return Map.copyOf(fields);
	}

	/**
	 * Build the journal capture from the ACP run alone.
	 *
	 * <p>
	 * No trajectory parsing is involved, and none is possible: {@code GrokSessionParser}
	 * reads the CLI's {@code streaming-json} stdout, whereas the durable
	 * {@code updates.jsonl} is a record of ACP notifications in a different envelope.
	 * That it is nevertheless reconstructible from the protocol is a fact about Grok, not
	 * about ACP — Grok returns its full usage vector on the prompt response, where Junie
	 * returns none and reports cost only inside its trajectory.
	 */
	@Override
	public Object capture(AcpRunRecord run) {
		Map<String, Object> usage = usage(run);
		List<GrokToolUseRecord> toolUses = new ArrayList<>();
		for (AcpToolStep step : run.toolSteps()) {
			toolUses.add(new GrokToolUseRecord(step.toolCallId(), step.name(), ToolKind.fromWireValue(step.kind()),
					step.rawInput(), step.rawOutput(), step.status(), "failed".equals(step.status()), null));
		}
		// modelId sits on the outer _meta, not inside usage: the outer block reports the
		// last model call and `usage` reports the turn total, and it is the turn total
		// that a capture should carry.
		return new GrokPhaseCapture(run.sessionId(), run.promptText(), stringValue(run.promptMeta().get("modelId")),
				intValue(usage.get("inputTokens")), intValue(usage.get("outputTokens")),
				intValue(usage.get("reasoningTokens")), intValue(usage.get("cacheCreationTokens")),
				intValue(usage.get("cachedReadTokens")), costUsd(usage), run.sessionId(),
				intValue(usage.get("numTurns")), !run.endedTurn(), run.stopReason(), run.answer(), run.thinking(),
				List.copyOf(toolUses));
	}

	@Override
	public String defaultModelLabel() {
		return "grok-default";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> usage(AcpRunRecord run) {
		Object usage = run.promptMeta().get("usage");
		return (usage instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
	}

	private static double costUsd(Map<String, Object> usage) {
		if (usage.get("costUsdTicks") instanceof Number ticks) {
			return BigDecimal.valueOf(ticks.longValue()).divide(TICKS_PER_USD, 10, RoundingMode.HALF_UP).doubleValue();
		}
		return 0.0;
	}

	private static int intValue(Object value) {
		return (value instanceof Number number) ? number.intValue() : 0;
	}

	private static String stringValue(Object value) {
		return (value instanceof String string) ? string : null;
	}

	private static Path defaultSessionsDirectory() {
		return Paths.get(System.getProperty("user.home"), ".grok", "sessions");
	}

}
