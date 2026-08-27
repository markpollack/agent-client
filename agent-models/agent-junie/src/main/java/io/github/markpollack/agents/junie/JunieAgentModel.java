/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.nio.file.Path;

import io.github.markpollack.agents.acp.AcpAgentModel;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * Adapter from the agent-client SPI to the JetBrains Junie CLI, driven over the Agent
 * Client Protocol.
 *
 * <h2>The protocol code no longer lives here</h2>
 *
 * <p>
 * This class originally carried the whole ACP lifecycle, deliberately, on the grounds
 * that a generic layer should be extracted from a second implementation rather than
 * designed from the first. Grok is that second implementation, and the extraction has
 * happened: the lifecycle, the update fold and the response mapping are now
 * {@link AcpAgentModel}, and everything that turned out to be specifically Junie is
 * {@link JunieAcpProfile}.
 *
 * <p>
 * What survives here is the type itself, its builder and its options, because those are
 * the published surface a consumer depends on. A Junie user writes the same code as
 * before.
 *
 * <h2>Junie is not wrapped in a CLI SDK</h2>
 *
 * <p>
 * Every non-ACP provider in this project shells out to a CLI and parses its stdout, which
 * is why each has a {@code provider-sdks} module. Junie speaks ACP, so the published ACP
 * client is the transport and no bespoke SDK is warranted — the same call this project
 * already makes for Claude, which uses the external {@code claude-code-sdk}.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class JunieAgentModel implements AgentModel {

	private final AcpAgentModel delegate;

	public JunieAgentModel(String command, JunieAgentOptions defaultOptions, Path sessionsDirectory,
			boolean captureEnabled) {
		this.delegate = AcpAgentModel.builder(new JunieAcpProfile(sessionsDirectory))
			.command(command)
			.defaultOptions((defaultOptions != null) ? defaultOptions : JunieAgentOptions.builder().build())
			.captureEnabled(captureEnabled)
			.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		return this.delegate.call(request);
	}

	@Override
	public boolean isAvailable() {
		return this.delegate.isAvailable();
	}

	public static final class Builder {

		private String command;

		private JunieAgentOptions defaultOptions;

		private Path sessionsDirectory;

		private boolean captureEnabled = true;

		private Builder() {
		}

		public Builder command(String command) {
			this.command = command;
			return this;
		}

		public Builder defaultOptions(JunieAgentOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Where Junie keeps its session directories. Defaults to
		 * {@code ~/.junie/sessions}; override for a CLI configured with a non-default
		 * home, or in tests.
		 * @param sessionsDirectory the sessions root
		 * @return this builder
		 */
		public Builder sessionsDirectory(Path sessionsDirectory) {
			this.sessionsDirectory = sessionsDirectory;
			return this;
		}

		/**
		 * Trajectory capture is enabled by default; this is the opt-out.
		 * @param captureEnabled whether to parse Junie's {@code events.jsonl}
		 * @return this builder
		 */
		public Builder captureEnabled(boolean captureEnabled) {
			this.captureEnabled = captureEnabled;
			return this;
		}

		public JunieAgentModel build() {
			return new JunieAgentModel(this.command, this.defaultOptions, this.sessionsDirectory, this.captureEnabled);
		}

	}

}
