/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk;

import java.nio.file.Path;

import io.github.markpollack.agents.groksdk.transport.CLITransport;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.ExecuteResult;

/**
 * Client for the xAI Grok CLI.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class GrokClient implements AutoCloseable {

	private final CLITransport transport;

	private final ExecuteOptions defaultOptions;

	private GrokClient(CLITransport transport, ExecuteOptions defaultOptions) {
		this.transport = transport;
		this.defaultOptions = defaultOptions;
	}

	public static GrokClient create() {
		return create(ExecuteOptions.defaults());
	}

	public static GrokClient create(ExecuteOptions options) {
		return create(options, null, null);
	}

	public static GrokClient create(ExecuteOptions options, Path workingDirectory) {
		return create(options, workingDirectory, null);
	}

	public static GrokClient create(ExecuteOptions options, Path workingDirectory, String grokCliPath) {
		ExecuteOptions effective = (workingDirectory == null) ? options
				: ExecuteOptions.builder()
					.model(options.getModel())
					.reasoningEffort(options.getReasoningEffort())
					.timeout(options.getTimeout())
					.workingDirectory(workingDirectory)
					.permissionMode(options.getPermissionMode())
					.maxTurns(options.getMaxTurns())
					.systemPromptOverride(options.getSystemPromptOverride())
					.jsonSchema(options.getJsonSchema())
					.allowedTools(options.getAllowedTools())
					.disallowedTools(options.getDisallowedTools())
					.disableWebSearch(options.isDisableWebSearch())
					.executablePath(options.getExecutablePath())
					.build();
		String cliPath = (grokCliPath != null) ? grokCliPath : options.getExecutablePath();
		return new GrokClient(new CLITransport(cliPath), effective);
	}

	public ExecuteResult execute(String prompt) {
		return this.transport.execute(prompt, this.defaultOptions);
	}

	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		return this.transport.execute(prompt, options);
	}

	public ExecuteResult resume(String sessionId, String prompt, ExecuteOptions options) {
		return this.transport.execute(prompt, options, sessionId);
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
