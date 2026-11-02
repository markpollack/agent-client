/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.transport.*;

record ClaudeClientConfiguration(Path directory, String executable, List<String> tools, Path captureDirectory,
		String cliVersion, Double maxBudgetUsd, List<PreToolHook> hooks) {
	record PreToolHook(String pattern, io.github.markpollack.claude.agent.sdk.hooks.HookCallback callback) {
	}

	io.github.markpollack.claude.agent.sdk.hooks.HookRegistry hookRegistry() {
		var registry = new io.github.markpollack.claude.agent.sdk.hooks.HookRegistry();
		this.hooks.forEach(hook -> registry.registerPreToolUse(hook.pattern(), hook.callback()));
		return registry;
	}

	Path directory(AgentOptions options) {
		return (options.getWorkingDirectory() == null ? directory : Path.of(options.getWorkingDirectory()))
			.toAbsolutePath()
			.normalize();
	}

	CLIOptions options(AgentOptions options, TransportByteObserver observer, String resume) {
		var nativeOptions = ClaudeAgentOptions.builder()
			.maxBudgetUsd(this.maxBudgetUsd)
			.model(options.getModel())
			.tools(tools)
			.yolo(options.isAutoApprove())
			.build();
		return ClaudeCliOptionsMapper.build(new AgentTaskRequest("", directory(options), options), nativeOptions,
				options.getTimeout(), observer, resume);
	}
}
