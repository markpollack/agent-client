/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

/** Read-only observation of resources still owned after a failed operation. */
public interface CleanupHandle {

	String id();

	CleanupSnapshot inspect();

}
