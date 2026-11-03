/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.util.UUID;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.codexsdk.transport.CodexProcess;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;

/**
 * Fixed direct-process generation, shared only by its one turn and source acquisition.
 */
final class CodexGeneration implements AutoCloseable {

	final String id = UUID.randomUUID().toString();

	final CodexConfiguration configuration;

	final ExecuteOptions options;

	final CodexProcess process;

	volatile String nativeId;

	volatile Integer exitCode;

	CodexGeneration(CodexConfiguration configuration, AgentOptions options) {
		this.configuration = configuration;
		this.options = configuration.options(options);
		this.process = new CodexProcess(configuration.executable(), this.options);
	}

	@Override
	public void close() {
		this.process.close();
	}

}
