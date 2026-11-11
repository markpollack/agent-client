/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.util.*;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.codexsdk.transport.CodexProcess;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;

/**
 * One direct process: a fresh thread or an exact resume of the emitted thread. Shared
 * only by its one turn and source acquisition.
 */
final class CodexGeneration implements AutoCloseable {

	final String id = UUID.randomUUID().toString();

	final CodexConfiguration configuration;

	final ExecuteOptions options;

	final CodexProcess process;

	/** Thread emitted by an earlier generation; null starts a fresh thread. */
	final String resumeId;

	final int turnIndex;

	volatile String nativeId;

	volatile String identitySource;

	volatile Integer exitCode;

	volatile Map<String, Object> wireUsage = Map.of();

	boolean prepared;

	/** Secondary recording lane for this process; null when capture is not configured. */
	volatile CodexWitness capture;

	CodexGeneration(CodexConfiguration configuration, AgentOptions options) {
		this(configuration, options, null, 0);
	}

	CodexGeneration(CodexConfiguration configuration, AgentOptions options, String resumeId, int turnIndex) {
		this.configuration = configuration;
		this.options = configuration.options(options);
		this.resumeId = resumeId;
		this.turnIndex = turnIndex;
		this.process = new CodexProcess(configuration.executable(), this.options, resumeId, configuration.untimed());
	}

	@Override
	public void close() {
		this.process.close();
	}

}
