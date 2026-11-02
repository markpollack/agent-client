/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import io.github.markpollack.agents.model.AgentResponse;

/**
 * Immutable reference to sealed acquired sources; not a canonical journal or task
 * verdict.
 *
 * @param manifest absolute acquisition manifest path
 * @param sha256 manifest SHA-256 at sealing
 * @param complete whether required acquisition completed without a known capture error
 * @param invocationId caller invocation identity
 * @param sessionId observed native session identity, or null when unavailable
 */
public record ClaudeAcquisition(Path manifest, String sha256, boolean complete, String invocationId,
		String sessionId) implements io.github.markpollack.agents.model.AgentMetadataValue {

	@Override
	public ClaudeAcquisition snapshot() {
		return new ClaudeAcquisition(manifest == null ? null : Path.of(manifest.toString()), sha256, complete,
				invocationId, sessionId);
	}

	/**
	 * Reads the provider reference without adding a Journal dependency to Client core.
	 * @param response execution response
	 * @return reference, or null when acquisition was not configured
	 */
	public static ClaudeAcquisition from(AgentResponse response) {
		Object value = response.getMetadata().get("claudeAcquisition");
		return value instanceof ClaudeAcquisition acquisition ? acquisition : null;
	}
}
