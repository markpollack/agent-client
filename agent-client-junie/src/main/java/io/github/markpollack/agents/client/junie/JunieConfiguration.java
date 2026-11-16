/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.nio.file.Path;

import io.github.markpollack.agents.model.AgentOptions;

record JunieConfiguration(String executable, Path workingDirectory, Path sessionsDirectory,
		Path acquisitionDirectory) {

	JunieConfiguration {
		workingDirectory = workingDirectory.toAbsolutePath().normalize();
		sessionsDirectory = sessionsDirectory.toAbsolutePath().normalize();
		acquisitionDirectory = acquisitionDirectory == null ? null : acquisitionDirectory.toAbsolutePath().normalize();
	}

	/** The project directory an execution runs in: the options' when set. */
	Path workingDirectory(AgentOptions options) {
		String named = options == null ? null : options.getWorkingDirectory();
		return named == null ? this.workingDirectory : Path.of(named).toAbsolutePath().normalize();
	}

}
