/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.util.List;
import java.util.Map;

/**
 * One logical tool call, folded from every ACP update that shares its {@code toolCallId}.
 *
 * <p>
 * <strong>A tool step is not a wire line.</strong> Both measured ACP agents emit several
 * updates per tool call, and counting updates overstates tool use severalfold: Grok 1.0.5
 * sends six updates for two tools, and Junie 26.8.24 folds twenty-one raw lines into four
 * steps. Any metric taken from unfolded updates is wrong by a multiple that varies per
 * agent, which makes it worse than useless for comparing agents.
 *
 * @param toolCallId the ACP correlation id, and the identity of this step
 * @param name the machine-authored tool identifier where one exists, else the first title
 * @param title the first human-readable title the agent gave this call
 * @param kind the ACP tool kind (read, edit, execute, ...), null if never sent
 * @param status the last status the agent reported for this call
 * @param locations file paths the agent said this call touched
 * @param rawInput the agent's own tool input, verbatim and uninterpreted
 * @param rawOutput the agent's own tool output, verbatim and uninterpreted
 * @author Mark Pollack
 * @since 0.30.0
 */
public record AcpToolStep(String toolCallId, String name, String title, String kind, String status,
		List<String> locations, Map<String, Object> rawInput, Map<String, Object> rawOutput) {

	public AcpToolStep {
		locations = (locations == null) ? List.of() : List.copyOf(locations);
	}

	/**
	 * Whether the agent itself reported this call as completed.
	 *
	 * <p>
	 * This reads the inner tool's own status rather than any wrapper completion state,
	 * because a turn that ends successfully says nothing about whether an individual tool
	 * within it failed.
	 * @return true when the reported status is {@code completed}
	 */
	public boolean isCompleted() {
		return "completed".equals(this.status);
	}

}
