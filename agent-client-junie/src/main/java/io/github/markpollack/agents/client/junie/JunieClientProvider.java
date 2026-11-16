/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.Capability;
import io.github.markpollack.agents.client.SessionCapabilities;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Junie on the Client lifecycle: each execution is one fresh synchronous Junie ACP
 * session, run through the same {@code AcpAgentModel} and {@code JunieAcpProfile} as
 * {@code JunieAgentModel}. Use it with {@code AgentClient.create(provider)} and
 * {@code goal(..).run()}; retained sessions are refused.
 *
 * <p>
 * With an {@link Builder#acquisitionDirectory(Path) acquisition directory}, each accepted
 * execution seals a {@code NativeGeneration}: the native session directory that Junie
 * named with the ACP session id is copied ({@code summary.json} and
 * {@code events.jsonl}), hashed and described in an {@code acquisition.json} manifest
 * under Agent Journal's profile {@code junie-<version>-acp-session-files}. A recorder
 * without Journal's Junie qualifier reports the generation {@code UNQUALIFIED}; the
 * execution itself is unaffected.
 */
public final class JunieClientProvider implements AgentExecutionProvider {

	private final JunieConfiguration configuration;

	private JunieClientProvider(Builder builder) {
		this.configuration = new JunieConfiguration(builder.executable, builder.workingDirectory,
				builder.sessionsDirectory, builder.acquisitionDirectory);
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
			throw new UnsupportedOperationException("Retained Junie sessions are not supported");
		return new JunieConnection(this.configuration, options);
	}

	@Override
	public SessionCapabilities capabilities() {
		var no = new Capability(false, Optional.of("Only one fresh Junie execution per session is supported"));
		return new SessionCapabilities(no, no, no);
	}

	public static final class Builder {

		private String executable = "junie";

		private Path workingDirectory = Path.of(".").toAbsolutePath().normalize();

		private Path sessionsDirectory = Path.of(System.getProperty("user.home"), ".junie", "sessions");

		private Path acquisitionDirectory;

		private Builder() {
		}

		/** The Junie CLI to launch with {@code --acp true}; default {@code junie}. */
		public Builder executable(String executable) {
			this.executable = Objects.requireNonNull(executable, "executable");
			return this;
		}

		/** The project directory, used when the options name none. */
		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
			return this;
		}

		/** Where Junie writes its native session directories; default ~/.junie/sessions. */
		public Builder nativeSessionsDirectory(Path sessionsDirectory) {
			this.sessionsDirectory = Objects.requireNonNull(sessionsDirectory, "sessionsDirectory");
			return this;
		}

		/** Seal each accepted execution's native session into a new directory here. */
		public Builder acquisitionDirectory(Path acquisitionDirectory) {
			this.acquisitionDirectory = Objects.requireNonNull(acquisitionDirectory, "acquisitionDirectory");
			return this;
		}

		public JunieClientProvider build() {
			return new JunieClientProvider(this);
		}

	}

}
