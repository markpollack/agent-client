/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What one ACP run produced, after folding.
 *
 * <p>
 * This is the boundary the per-agent {@link AcpAgentProfile} sees. It carries the
 * protocol's own portable core — answer text, thinking text, tool steps keyed by
 * {@code toolCallId}, stop reason — plus the two things that turned out to be per-agent:
 * the located {@link #trajectory()} and the uninterpreted {@link #promptMeta()} the agent
 * attached to its prompt response.
 *
 * @param sessionId the ACP session id
 * @param promptText the prompt as sent
 * @param answer concatenated assistant message text
 * @param thinking concatenated agent thought text
 * @param stopReason the ACP stop reason, verbatim
 * @param toolSteps folded tool calls, in first-seen order
 * @param thoughtChunkCount how many raw thought updates arrived, before folding
 * @param messageChunkCount how many raw message updates arrived, before folding
 * @param unknownUpdateKinds update kinds this SDK version could not type, with counts
 * @param agentName the agent's self-reported name from {@code initialize}
 * @param agentVersion the agent's self-reported version from {@code initialize}
 * @param promptMeta the {@code _meta} map from the ACP prompt response, uninterpreted
 * @param trajectory the agent's own durable trajectory, or null when it keeps none
 * @param duration wall-clock duration of the run
 * @author Mark Pollack
 * @since 0.30.0
 */
public record AcpRunRecord(String sessionId, String promptText, String answer, String thinking, String stopReason,
		List<AcpToolStep> toolSteps, int thoughtChunkCount, int messageChunkCount,
		Map<String, Integer> unknownUpdateKinds, String agentName, String agentVersion, Map<String, Object> promptMeta,
		Path trajectory, Duration duration) {

	public AcpRunRecord {
		toolSteps = (toolSteps == null) ? List.of() : List.copyOf(toolSteps);
		unknownUpdateKinds = (unknownUpdateKinds == null) ? Map.of() : Map.copyOf(unknownUpdateKinds);
		promptMeta = (promptMeta == null) ? Map.of() : Map.copyOf(promptMeta);
	}

	/**
	 * Whether the agent ended its turn normally.
	 * @return true when the stop reason is {@code end_turn}
	 */
	public boolean endedTurn() {
		return "end_turn".equalsIgnoreCase(this.stopReason);
	}

}
