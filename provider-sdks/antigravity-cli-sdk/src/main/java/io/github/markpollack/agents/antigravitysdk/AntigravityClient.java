/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk;

import java.nio.file.Path;

import io.github.markpollack.agents.antigravitysdk.transport.CLITransport;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteResult;

/**
 * Client for the Google Antigravity CLI ({@code agy}).
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class AntigravityClient implements AutoCloseable {

	private final CLITransport transport;

	private final ExecuteOptions defaultOptions;

	private AntigravityClient(CLITransport transport, ExecuteOptions defaultOptions) {
		this.transport = transport;
		this.defaultOptions = defaultOptions;
	}

	public static AntigravityClient create() {
		return create(ExecuteOptions.defaults());
	}

	public static AntigravityClient create(ExecuteOptions options) {
		return create(options, null, null);
	}

	public static AntigravityClient create(ExecuteOptions options, Path workingDirectory) {
		return create(options, workingDirectory, null);
	}

	public static AntigravityClient create(ExecuteOptions options, Path workingDirectory, String agyCliPath) {
		ExecuteOptions effective = (workingDirectory == null) ? options
				: ExecuteOptions.builder()
					.model(options.getModel())
					.effort(options.getEffort())
					.timeout(options.getTimeout())
					.workingDirectory(workingDirectory)
					.additionalDirectories(options.getAdditionalDirectories())
					.dangerouslySkipPermissions(options.isDangerouslySkipPermissions())
					.sandbox(options.isSandbox())
					.mode(options.getMode())
					.jsonSchema(options.getJsonSchema())
					.agent(options.getAgent())
					.executablePath(options.getExecutablePath())
					.build();
		String cliPath = (agyCliPath != null) ? agyCliPath : options.getExecutablePath();
		return new AntigravityClient(new CLITransport(cliPath), effective);
	}

	public ExecuteResult execute(String prompt) {
		return this.transport.execute(prompt, this.defaultOptions);
	}

	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		return this.transport.execute(prompt, options);
	}

	public ExecuteResult resume(String conversationId, String prompt, ExecuteOptions options) {
		return this.transport.execute(prompt, options, conversationId);
	}

	public boolean isAvailable() {
		return this.transport.isAvailable();
	}

	public ExecuteOptions getDefaultOptions() {
		return this.defaultOptions;
	}

	@Override
	public void close() {
		// Each execution is its own subprocess; there is nothing long-lived to release.
	}

}
