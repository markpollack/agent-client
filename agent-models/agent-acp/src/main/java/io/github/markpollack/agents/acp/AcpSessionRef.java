/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Everything known about a finished ACP session that could plausibly be needed to find
 * the agent's own durable trajectory on disk.
 *
 * <p>
 * This record exists because the three ACP CLIs measured for this project locate their
 * trajectories in three different ways, and no two of them need the same inputs:
 *
 * <table border="1">
 * <caption>Trajectory addressing, measured 2026-08-26</caption>
 * <tr>
 * <th>Agent</th>
 * <th>Location</th>
 * <th>Needs</th>
 * </tr>
 * <tr>
 * <td>Junie 26.8.24</td>
 * <td>{@code ~/.junie/sessions/<sessionId>/events.jsonl}</td>
 * <td>session id</td>
 * </tr>
 * <tr>
 * <td>Grok 1.0.5</td>
 * <td>{@code ~/.grok/sessions/<urlencoded cwd>/<sessionId>/updates.jsonl}</td>
 * <td>session id <em>and</em> working directory</td>
 * </tr>
 * <tr>
 * <td>Gemini CLI 0.54.4</td>
 * <td>{@code ~/.gemini/tmp/<slug>/chats/session-<timestamp>-<first 8 of sessionId>.jsonl}</td>
 * <td>session id prefix <em>and</em> a start time to disambiguate</td>
 * </tr>
 * </table>
 *
 * <p>
 * That spread is the whole reason {@link AcpTrajectoryLocator} is a seam rather than a
 * method on the model. Protocol handling is genuinely shared across these three agents;
 * finding what the run wrote down is not.
 *
 * @param sessionId the id returned by ACP {@code session/new}
 * @param workingDirectory the directory the session was created against
 * @param startedAt when the prompt was issued, for agents that name files by time
 * @author Mark Pollack
 * @since 0.30.0
 */
public record AcpSessionRef(String sessionId, Path workingDirectory, Instant startedAt) {
}
