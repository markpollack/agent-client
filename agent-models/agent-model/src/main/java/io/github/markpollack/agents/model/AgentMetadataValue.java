/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.model;

/**
 * Provider-owned typed metadata detachment. Implementations must return an immutable,
 * deeply detached value with no live transport, resource or mutable collection aliases.
 * This is a trusted provider contract, like response conversion, not reflective copying.
 */
public interface AgentMetadataValue {

	AgentMetadataValue snapshot();

}
