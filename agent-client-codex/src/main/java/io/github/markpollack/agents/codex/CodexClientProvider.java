/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;

/**
 * Codex exec on the Client lifecycle. One-shot runs one fresh process; a retained session
 * resumes the exact emitted thread with a replacement process per turn.
 */
public final class CodexClientProvider implements AgentExecutionProvider {

	private final CodexConfiguration configuration;

	private CodexClientProvider(Builder builder) {
		this.configuration = new CodexConfiguration(builder.executable, builder.directory, builder.sessions,
				builder.capture, builder.version, builder.sandbox, builder.skipGitCheck, builder.captureObserver);
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
		return new CodexConnection(this.configuration, options, retained);
	}

	@Override
	public SessionCapabilities capabilities() {
		return new SessionCapabilities(new Capability(true, Optional.empty()),
				new Capability(false, Optional.of("Cross-handle thread loading is not qualified")),
				new Capability(false, Optional.of("Native progress is not implemented")));
	}

	public static final class Builder {

		private String executable = "codex";

		private Path directory = Path.of(".").toAbsolutePath().normalize();

		private Path sessions;

		private Path capture;

		private String version = "0.166.0";

		private SandboxMode sandbox = SandboxMode.READ_ONLY;

		private boolean skipGitCheck;

		private java.util.function.Consumer<CodexWitness> captureObserver;

		/** Package-private test seam: observe each dispatched turn's capture handle. */
		Builder captureObserver(java.util.function.Consumer<CodexWitness> observer) {
			this.captureObserver = observer;
			return this;
		}

		public Builder codexPath(String executable) {
			this.executable = Objects.requireNonNull(executable);
			return this;
		}

		public Builder workingDirectory(Path directory) {
			this.directory = Objects.requireNonNull(directory);
			return this;
		}

		/**
		 * Exact native sessions tree to search by observed thread identity, never
		 * recency.
		 */
		public Builder nativeSessionsDirectory(Path directory) {
			this.sessions = Objects.requireNonNull(directory);
			return this;
		}

		/**
		 * Capture original bytes on the optional recording lane; version is checked
		 * against native metadata.
		 */
		public Builder acquisitionDirectory(Path directory, String version) {
			this.capture = Objects.requireNonNull(directory);
			this.version = Objects.requireNonNull(version);
			return this;
		}

		public Builder sandbox(SandboxMode sandbox) {
			this.sandbox = Objects.requireNonNull(sandbox);
			return this;
		}

		public Builder skipGitCheck(boolean skip) {
			this.skipGitCheck = skip;
			return this;
		}

		public CodexClientProvider build() {
			return new CodexClientProvider(this);
		}

	}

}
