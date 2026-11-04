/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.util.UUID;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.antigravitysdk.transport.AntigravityProcess;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;

/**
 * Fixed direct-process generation, shared only by its one turn and source acquisition.
 */
final class AntigravityGeneration implements AutoCloseable {

	final String id = UUID.randomUUID().toString();

	final AntigravityConfiguration configuration;

	final ExecuteOptions options;

	final AntigravityProcess process;

	volatile String nativeId;

	volatile Integer exitCode;

	AntigravityGeneration(AntigravityConfiguration configuration, AgentOptions options) {
		this.configuration = configuration;
		this.options = configuration.options(options);
		this.process = new AntigravityProcess(configuration.executable(), this.options,
				options.getEnvironmentVariables() == null ? java.util.Map.of() : options.getEnvironmentVariables());
	}

	@Override
	public void close() {
		this.process.close();
	}

}
