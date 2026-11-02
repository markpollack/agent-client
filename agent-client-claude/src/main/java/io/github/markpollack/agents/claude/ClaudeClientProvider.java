/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Claude SDK transport adapted to the public AgentClient lifecycle. No direct turn API.
 */
public final class ClaudeClientProvider implements AgentExecutionProvider {

	private final ClaudeClientConfiguration config;

	private ClaudeClientProvider(Builder builder) {
		this.config = new ClaudeClientConfiguration(builder.directory, builder.executable,
				builder.tools == null ? null : List.copyOf(builder.tools), builder.captureDirectory, builder.cliVersion,
				builder.maxBudgetUsd, List.copyOf(builder.hooks));
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Connection open(AgentOptions options) {
		return open(options, true);
	}

	@Override
	public Connection open(AgentOptions options, boolean retained) {
		var connection = new ClaudeClientConnection(this.config, options, retained);
		try {
			connection.connect();
			return connection;
		}
		catch (RuntimeException failure) {
			throw new ConnectionAcquisitionException("Claude acquisition failed", failure, connection);
		}
	}

	@Override
	public SessionCapabilities capabilities() {
		return new SessionCapabilities(new Capability(true, Optional.empty()),
				new Capability(false, Optional.of("Cross-handle/restart ownership exclusion is not qualified")),
				new Capability(true, Optional.empty()));
	}

	public static final class Builder {

		private Path directory = Path.of(".").toAbsolutePath().normalize();

		private String executable;

		private List<String> tools;

		private Path captureDirectory;

		private String cliVersion = "unknown";

		private Double maxBudgetUsd;

		private final List<ClaudeClientConfiguration.PreToolHook> hooks = new ArrayList<>();

		/** Native spending cap, fixed for every connection generation. */
		public Builder maxBudgetUsd(double dollars) {
			if (!Double.isFinite(dollars) || dollars <= 0)
				throw new IllegalArgumentException("Positive finite budget required");
			this.maxBudgetUsd = dollars;
			return this;
		}

		/** Required pre-tool permission hook; registrations are frozen at build. */
		public Builder preToolUse(String pattern, io.github.markpollack.claude.agent.sdk.hooks.HookCallback callback) {
			this.hooks.add(new ClaudeClientConfiguration.PreToolHook(Objects.requireNonNull(pattern),
					Objects.requireNonNull(callback)));
			return this;
		}

		/**
		 * Enable bounded one-shot byte acquisition, materialized by the configured
		 * recorder after result acceptance.
		 */
		public Builder acquisitionDirectory(Path directory, String cliVersion) {
			this.captureDirectory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
			this.cliVersion = Objects.requireNonNull(cliVersion);
			return this;
		}

		public Builder workingDirectory(Path directory) {
			this.directory = Objects.requireNonNull(directory);
			return this;
		}

		public Builder claudePath(String executable) {
			this.executable = executable;
			return this;
		}

		public Builder tools(List<String> tools) {
			this.tools = List.copyOf(tools);
			return this;
		}

		public ClaudeClientProvider build() {
			return new ClaudeClientProvider(this);
		}

	}

}
