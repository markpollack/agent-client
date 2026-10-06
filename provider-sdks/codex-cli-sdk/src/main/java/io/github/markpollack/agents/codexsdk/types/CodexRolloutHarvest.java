/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import java.util.List;
import java.util.Optional;

/**
 * The rollouts harvested for one Codex execution: the root thread's file first, then
 * every sub-agent file reached by following
 * {@code SubAgentActivity{kind:"started"}.agent_thread_id} from the root, recursively.
 *
 * @param rollouts the root followed by its descendants in discovery order (breadth
 * first); empty when no root was found within the bounded wait
 * @param missingThreadIds sub-agent thread ids that the parent file announced but whose
 * rollout file was not found within the bounded wait. Reported, never fatal: the capture
 * layer lists such spawns as not collected.
 */
public record CodexRolloutHarvest(List<CodexRollout> rollouts, List<String> missingThreadIds) {

	public static final CodexRolloutHarvest EMPTY = new CodexRolloutHarvest(List.of(), List.of());

	public CodexRolloutHarvest {
		rollouts = rollouts != null ? List.copyOf(rollouts) : List.of();
		missingThreadIds = missingThreadIds != null ? List.copyOf(missingThreadIds) : List.of();
	}

	/**
	 * @return the root rollout, i.e. the first one without a parent thread id
	 */
	public Optional<CodexRollout> root() {
		return rollouts.stream().filter(CodexRollout::isRoot).findFirst();
	}

	/**
	 * @return the verbatim lines of the root rollout, or an empty list when none was
	 * found
	 */
	public List<String> rootLines() {
		return root().map(CodexRollout::lines).orElse(List.of());
	}

}
