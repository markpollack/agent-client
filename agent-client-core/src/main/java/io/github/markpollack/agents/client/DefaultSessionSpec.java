/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import io.github.markpollack.agents.model.AgentOptions;

final class DefaultSessionSpec implements SessionSpec {

	AgentOptions options;

	DefaultSessionSpec(AgentOptions options) {
		this.options = options;
	}

	@Override
	public SessionSpec options(AgentOptions options) {
		this.options = java.util.Objects.requireNonNull(options, "options");
		return this;
	}

	@Override
	public SessionSpec workingDirectory(Path directory) {
		this.options = DefaultAgentOptions.builder().from(this.options).workingDirectory(directory.toString()).build();
		return this;
	}

}
