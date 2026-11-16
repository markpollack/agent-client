/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.Objects;
import java.util.Optional;

/**
 * What a lifecycle provider supports beyond one synchronous execution.
 *
 * @param retention retained sessions across executions
 * @param load exact-history loading
 * @param progress native progress events
 * @param asynchronous {@code runAsync()}; a provider that refuses it is run with
 * {@code run()} only, and {@code runAsync()} fails fast with
 * {@link UnsupportedOperationException}
 */
public record SessionCapabilities(Capability retention, Capability load, Capability progress,
		Capability asynchronous) {

	public SessionCapabilities {
		Objects.requireNonNull(asynchronous, "asynchronous");
	}

	/**
	 * Capabilities of a provider that supports {@code runAsync()}.
	 * @param retention retained sessions across executions
	 * @param load exact-history loading
	 * @param progress native progress events
	 */
	public SessionCapabilities(Capability retention, Capability load, Capability progress) {
		this(retention, load, progress, new Capability(true, Optional.empty()));
	}

}
