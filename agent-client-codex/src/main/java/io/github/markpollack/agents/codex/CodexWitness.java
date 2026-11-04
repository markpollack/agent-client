/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

/**
 * Secondary recording lane of one generation, reachable from the lifecycle without a
 * class cycle: observe the native rollout once, seal the originals once.
 */
interface CodexWitness {

	void observeNative();

	CodexAcquisition seal();

	CodexNativeSource.Observed nativeObservation();

}
