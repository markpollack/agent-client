/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.time.Duration;
import java.util.UUID;
import io.github.markpollack.claude.agent.sdk.*;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.agents.model.AgentOptions;

/** One exact SDK target; closing never retargets a successor. */
final class ClaudeClientGeneration {

	final String id = UUID.randomUUID().toString();

	final ClaudeSyncClient client;

	final ClaudeClientSources sources;

	final CLIOptions cli;

	final java.nio.file.Path directory;

	final ClaudeClientConfiguration config;

	volatile boolean dispatched;

	final boolean reconnected;

	private final java.util.concurrent.locks.ReentrantLock closeLock = new java.util.concurrent.locks.ReentrantLock();

	ClaudeClientGeneration(ClaudeClientConfiguration config, AgentOptions options, String resume, boolean retained) {
		this.config = config;
		this.reconnected = resume != null;
		this.directory = config.directory(options);
		this.sources = !retained && config.captureDirectory() != null ? new ClaudeClientSources() : null;
		this.cli = config.options(options, this.sources == null ? null : this.sources::observe, resume);
		this.client = ClaudeClient.sync(this.cli)
			.workingDirectory(this.directory)
			.timeout(options.getTimeout() == null ? Duration.ofMinutes(2) : options.getTimeout())
			.claudePath(config.executable())
			.hookRegistry(config.hookRegistry())
			.build();
	}

	void connect() {
		this.client.connect();
	}

	void close() {
		boolean interrupted = Thread.interrupted();
		this.closeLock.lock();
		try {
			this.client.close();
			var snapshot = this.client.transportResources();
			if (snapshot.isEmpty() || !snapshot.orElseThrow().observedResourcesTerminated())
				throw new IllegalStateException("Claude observed-resource cessation is unconfirmed: " + this.id);
		}
		finally {
			this.closeLock.unlock();
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

}
