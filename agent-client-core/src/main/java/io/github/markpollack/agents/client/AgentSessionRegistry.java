/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.function.Consumer;

/** Client-owned retained conversations; native loading requires a later adapter. */
public interface AgentSessionRegistry {

	AgentSession create(Consumer<SessionSpec> configure);

	default AgentSession load(ProviderReference reference, Consumer<SessionSpec> configure) {
		throw new UnsupportedOperationException("Provider-reference loading is not implemented");
	}

}
