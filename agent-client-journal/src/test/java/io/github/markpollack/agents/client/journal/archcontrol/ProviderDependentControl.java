/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal.archcontrol;

/**
 * Negative control for {@code RecorderBoundaryTest}: a class in the recorder's package
 * tree that depends on a provider type. The boundary rule must report it.
 */
public final class ProviderDependentControl {

	private ProviderDependentControl() {
	}

	/** A bytecode dependency on a provider package. */
	public static Class<?> provider() {
		return io.github.markpollack.agents.claude.ClaudeAcquisition.class;
	}

}
