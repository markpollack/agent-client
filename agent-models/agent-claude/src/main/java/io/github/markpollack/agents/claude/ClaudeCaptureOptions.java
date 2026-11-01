/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for one blocking invocation's private, lossless acquired source package.
 *
 * @param directory a new output directory; existing paths are never overwritten
 * @param invocationId caller-owned invocation identity
 * @param cliVersion actual caller-observed CLI version, not a compatibility claim
 * @param nativeProjectsDirectory native Claude projects root
 * @param provenance caller-supplied exact build/version provenance, never credentials
 */
public record ClaudeCaptureOptions(Path directory, String invocationId, String cliVersion, Path nativeProjectsDirectory,
		Map<String, String> provenance) {
	public ClaudeCaptureOptions {
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(nativeProjectsDirectory, "nativeProjectsDirectory");
		if (invocationId == null || invocationId.isBlank() || cliVersion == null || cliVersion.isBlank()) {
			throw new IllegalArgumentException("Invocation ID and actual CLI version are required");
		}
		directory = directory.toAbsolutePath().normalize();
		nativeProjectsDirectory = nativeProjectsDirectory.toAbsolutePath().normalize();
		provenance = Map.copyOf(provenance);
	}

	/**
	 * Uses the ordinary native Claude projects directory.
	 * @param directory new private package directory
	 * @param invocationId caller invocation identity
	 * @param cliVersion observed CLI version
	 */
	public ClaudeCaptureOptions(Path directory, String invocationId, String cliVersion) {
		this(directory, invocationId, cliVersion, Path.of(System.getProperty("user.home"), ".claude", "projects"),
				Map.of());
	}
}
