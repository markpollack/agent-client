/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import java.util.List;

/**
 * One persisted Codex rollout file, copied verbatim at harvest time.
 * <p>
 * Codex writes one rollout per thread under its session store
 * ({@code sessions/YYYY/MM/DD/rollout-<timestamp>-<threadId>.jsonl}). A sub-agent is its
 * own thread with its own file; the parent's file names the child only through its
 * {@code SubAgentActivity} records. The lines are copied once, when the run is harvested;
 * the files themselves stay Codex's. This type carries discovery results only; parsing
 * the records is the capture layer's job.
 *
 * @param threadId the thread this rollout belongs to: {@code session_meta.payload.id} of
 * the file's first {@code session_meta} record, or the id taken from the file name when
 * the record is absent. Never the child's {@code session_id}, which is the root's id.
 * @param parentThreadId {@code session_meta.payload.parent_thread_id} of the first
 * {@code session_meta}; {@code null} for the root rollout
 * @param sourcePath the absolute path the lines were read from, as a string kept for
 * evidence; the file is not copied
 * @param lines the verbatim JSONL lines, immutable
 */
public record CodexRollout(String threadId, String parentThreadId, String sourcePath, List<String> lines) {

	public CodexRollout {
		lines = lines != null ? List.copyOf(lines) : List.of();
	}

	/**
	 * @return {@code true} when this rollout has no parent, i.e. it is the root thread
	 */
	public boolean isRoot() {
		return parentThreadId == null;
	}

}
