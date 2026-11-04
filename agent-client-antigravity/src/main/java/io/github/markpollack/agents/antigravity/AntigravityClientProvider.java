/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.nio.file.Path;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Fresh Antigravity exec on the Client lifecycle; retained conversations are unsupported.
 */
public final class AntigravityClientProvider implements AgentExecutionProvider {

	private final AntigravityConfiguration configuration;

	private AntigravityClientProvider(Builder builder) {
		this.configuration = new AntigravityConfiguration(builder.executable, builder.directory, builder.capture,
				builder.version, builder.sandbox);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Connection open(AgentOptions options) {
		return open(options, false);
	}

	@Override
	public Connection open(AgentOptions options, boolean retained) {
		if (retained)
			throw new UnsupportedOperationException("Retained Antigravity is not supported");
		return new AntigravityConnection(this.configuration, options);
	}

	@Override
	public SessionCapabilities capabilities() {
		var no = new Capability(false, Optional.of("Only one fresh Antigravity execution is supported"));
		return new SessionCapabilities(no, no, no);
	}

	public static final class Builder {

		private String executable = "agy";

		private Path directory = Path.of(".").toAbsolutePath().normalize();

		private Path capture;

		private String version = "UNKNOWN";

		private boolean sandbox;

		public Builder executable(String executable) {
			this.executable = Objects.requireNonNull(executable);
			return this;
		}

		public Builder workingDirectory(Path directory) {
			this.directory = Objects.requireNonNull(directory);
			return this;
		}

		/**
		 * Capture original bytes; version is an asserted expectation, not native
		 * observation.
		 */
		public Builder acquisitionDirectory(Path directory, String version) {
			this.capture = Objects.requireNonNull(directory);
			this.version = Objects.requireNonNull(version);
			return this;
		}

		public Builder sandbox(boolean sandbox) {
			this.sandbox = sandbox;
			return this;
		}

		public AntigravityClientProvider build() {
			return new AntigravityClientProvider(this);
		}

	}

}
