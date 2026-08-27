/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the durable trajectory an ACP agent wrote for one session, or {@code null} if
 * there is none.
 *
 * <p>
 * <strong>This is the axis along which ACP agents actually differ.</strong> The protocol
 * itself normalises the live stream well enough that one model can drive Junie and Grok
 * unchanged; what none of it normalises is where the run's own record lands on disk, or
 * whether one exists at all. ACP has no notion of a session's persisted trajectory, so
 * this is necessarily out-of-band knowledge, per agent.
 *
 * <p>
 * It is deliberately a function of {@link AcpSessionRef} rather than of a session id
 * alone: Grok keys its session directories by working directory first and session id
 * second, so an id-only signature would have fit Junie and excluded Grok.
 *
 * <h2>A located trajectory may be secret-bearing</h2>
 *
 * <p>
 * Junie's {@code events.jsonl} contains the launching process's entire environment,
 * unredacted, including live API keys. Anything that copies, archives or normalises a
 * located trajectory must redact first. Returning the path is safe; moving the file is
 * not.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
@FunctionalInterface
public interface AcpTrajectoryLocator {

	/**
	 * Resolve the trajectory for a finished session.
	 * @param ref what is known about the session
	 * @return the trajectory file, or {@code null} when the agent keeps none, the session
	 * id is unusable, or the expected file is absent
	 */
	Path locate(AcpSessionRef ref);

	/**
	 * A locator for agents that write no durable trajectory. Capture is then limited to
	 * what the live ACP stream carried, which for tool-call detail is a real loss — see
	 * {@link AcpAgentModel} for what the two planes each hold.
	 * @return a locator that always returns {@code null}
	 */
	static AcpTrajectoryLocator none() {
		return ref -> null;
	}

	/**
	 * Return this locator's path only when it exists and is a regular file.
	 *
	 * <p>
	 * Wrapping is preferred to each locator repeating the check, because a locator that
	 * returns a plausible-but-absent path is the failure mode that produces a silently
	 * empty capture.
	 * @return a locator that yields only existing regular files
	 */
	default AcpTrajectoryLocator existingOnly() {
		return ref -> {
			Path candidate = locate(ref);
			return (candidate != null && Files.isRegularFile(candidate)) ? candidate : null;
		};
	}

}
