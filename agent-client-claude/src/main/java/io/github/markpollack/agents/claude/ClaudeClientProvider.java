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
				builder.maxBudgetUsd, List.copyOf(builder.hooks), builder.retainedTranscript, builder.freshProjects,
				builder.oneShotProjects);
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

		private Path retainedTranscript;

		private Path freshProjects;

		private Path oneShotProjects;

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
		 * Enable bounded byte acquisition. One-shot sources are materialized by the
		 * recorder after acceptance. Retained turns provide a shared
		 * ClaudeRetainedCapture handle to the recorder for explicit diagnostic export
		 * after session close; retained Journal archive qualification is not yet
		 * available.
		 */
		public Builder acquisitionDirectory(Path directory, String cliVersion) {
			this.captureDirectory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
			this.cliVersion = Objects.requireNonNull(cliVersion);
			return this;
		}

		/**
		 * Observe bounded native file prefixes before/after retained turns for the
		 * two-successful-text-turn archive profile. The exact file must be observable
		 * before first dispatch; absent prefixes remain unknown. Also configure an
		 * acquisitionDirectory. This does not establish native retained qualification.
		 */
		public Builder retainedTranscript(Path transcript) {
			this.retainedTranscript = Objects.requireNonNull(transcript).toAbsolutePath().normalize();
			return this;
		}

		/**
		 * Start each retained generation as a fresh native session that this Client names
		 * ({@code --session-id}), and observe its transcript under the native projects
		 * root from absence through final enumeration for Journal's fresh-start retained
		 * profile. Also configure an acquisitionDirectory. Reconnected generations resume
		 * the emitted identity and are not fresh starts.
		 * @param nativeProjectsRoot the CLI's projects directory, ordinarily
		 * {@code ~/.claude/projects}
		 */
		public Builder freshTranscript(Path nativeProjectsRoot) {
			this.freshProjects = Objects.requireNonNull(nativeProjectsRoot).toAbsolutePath().normalize();
			return this;
		}

		/**
		 * Search this native Claude projects root, instead of
		 * {@code user.home/.claude/projects}, for the exact observed session's transcript
		 * when sealing a one-shot acquisition. Use it when the CLI runs with a different
		 * home than this JVM, for example inside a sandbox: pass the host-visible path of
		 * the sandbox's {@code .claude/projects}. Unset, one-shot acquisition searches
		 * {@code user.home/.claude/projects} as before. This changes neither the child's
		 * working directory nor its {@code HOME}; it only says where to look. Lookup
		 * stays exact: one file named for the observed session, whose records name that
		 * session, or the transcript is reported unavailable and the acquisition
		 * incomplete. The root and the launch working directory are recorded, as given,
		 * in the manifest's artifact provenance. Also configure an acquisitionDirectory.
		 * Retained generations use {@link #retainedTranscript} or
		 * {@link #freshTranscript} instead; this setting does not affect them.
		 * @param nativeProjectsRoot host-visible native projects root the CLI writes to
		 */
		public Builder oneShotNativeProjects(Path nativeProjectsRoot) {
			this.oneShotProjects = Objects.requireNonNull(nativeProjectsRoot).toAbsolutePath().normalize();
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
			if (this.retainedTranscript != null && this.freshProjects != null)
				throw new IllegalArgumentException("An exact retained transcript and a fresh start exclude each other");
			requireAcquisition(this.freshProjects, "Fresh-start observation requires an acquisition directory");
			requireAcquisition(this.oneShotProjects,
					"A one-shot native projects root requires an acquisition directory");
			return new ClaudeClientProvider(this);
		}

		private void requireAcquisition(Path setting, String message) {
			if (setting != null && this.captureDirectory == null)
				throw new IllegalArgumentException(message);
		}

	}

}
