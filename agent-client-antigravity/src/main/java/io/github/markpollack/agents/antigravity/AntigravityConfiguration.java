/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.antigravitysdk.types.*;

record AntigravityConfiguration(String executable, Path directory, Path capture, String version, boolean sandbox) {
	ExecuteOptions options(AgentOptions options) {
		validate(options);
		var timeout = options.getTimeout() == null ? Duration.ofMinutes(3) : options.getTimeout();
		if (timeout.isNegative() || timeout.isZero())
			throw new IllegalArgumentException("Positive timeout required");
		return ExecuteOptions.builder()
			.workingDirectory(
					(options.getWorkingDirectory() == null ? this.directory : Path.of(options.getWorkingDirectory()))
						.toAbsolutePath()
						.normalize())
			.timeout(timeout)
			.model(options.getModel())
			.effort(options.getEffort())
			.dangerouslySkipPermissions(options.isAutoApprove())
			.sandbox(this.sandbox)
			.build();
	}

	private static void validate(AgentOptions options) {
		if (options.getMaxTurns() != null || options.getJsonSchema() != null
				|| !options.getMcpServerDefinitions().isEmpty() || !options.getExtras().isEmpty()
				|| options.getSystemInstructions() != null)
			throw new UnsupportedOperationException(
					"Antigravity one-shot supports noninteractive text, cwd, model, effort, environment and deadline only");
	}
}
