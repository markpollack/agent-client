/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.codexsdk.types.*;

record CodexConfiguration(String executable, Path directory, Path sessions, Path capture, String version,
		SandboxMode sandbox, boolean skipGitCheck, java.util.function.Consumer<CodexWitness> captureObserver) {

	CodexConfiguration(String executable, Path directory, Path sessions, Path capture, String version,
			SandboxMode sandbox, boolean skipGitCheck) {
		this(executable, directory, sessions, capture, version, sandbox, skipGitCheck, null);
	}

	/** Controlled tests observe each turn's capture handle; production passes nothing. */
	void observe(CodexWitness capture) {
		if (this.captureObserver != null)
			this.captureObserver.accept(capture);
	}

	Path sessions(ExecuteOptions options) {
		if (this.sessions != null)
			return this.sessions;
		String configured = options.getEnvironment().getOrDefault("CODEX_HOME", System.getenv("CODEX_HOME"));
		return (configured == null ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(configured))
			.resolve("sessions");
	}

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
			.reasoningEffort(options.getEffort())
			.environment(options.getEnvironmentVariables() == null ? Map.of() : options.getEnvironmentVariables())
			.fullAuto(false)
			.sandboxMode(this.sandbox)
			.approvalPolicy(ApprovalPolicy.NEVER)
			.jsonOutput(true)
			.skipGitCheck(this.skipGitCheck)
			.build();
	}

	private static void validate(AgentOptions options) {
		if (!options.isAutoApprove() || options.getMaxTurns() != null || options.getJsonSchema() != null
				|| !options.getMcpServerDefinitions().isEmpty() || !options.getExtras().isEmpty()
				|| options.getSystemInstructions() != null)
			throw new UnsupportedOperationException(
					"Codex one-shot supports noninteractive text, cwd, model, effort, environment and deadline only");
	}
}
