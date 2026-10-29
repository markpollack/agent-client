/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.codexsdk.exceptions.CodexSDKException;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.ExecuteResult;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Low-level transport layer for Codex CLI communication using zt-exec for robust process
 * management.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class CLITransport {

	private static final Logger logger = LoggerFactory.getLogger(CLITransport.class);

	private static final Duration ROLLOUT_WAIT = Duration.ofSeconds(2);

	private static final Duration ROLLOUT_POLL_INTERVAL = Duration.ofMillis(100);

	private final Path workingDirectory;

	private final String codexCliPath;

	public CLITransport(Path workingDirectory) {
		this(workingDirectory, null);
	}

	public CLITransport(Path workingDirectory, String codexCliPath) {
		this.workingDirectory = workingDirectory;
		this.codexCliPath = codexCliPath != null ? codexCliPath : CodexCliDiscovery.discoverCodexCli();

		// Validate the CLI is functional
		if (!CodexCliDiscovery.validateCodexCli(this.codexCliPath)) {
			throw new CodexSDKException("Codex CLI at " + this.codexCliPath + " is not functional");
		}

		logger.info("Codex CLI initialized at: {}", this.codexCliPath);
	}

	/**
	 * Execute a prompt via Codex CLI in execute mode.
	 * @param prompt the user prompt/goal to execute
	 * @param options execution options
	 * @return the execution result
	 * @throws CodexSDKException if execution fails
	 */
	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		if (prompt == null || prompt.isEmpty()) {
			throw new IllegalArgumentException("Prompt cannot be null or empty");
		}
		if (options == null) {
			options = ExecuteOptions.defaultOptions();
		}

		List<String> command = buildCommand(codexCliPath, prompt, options, null);
		return executeCommand(command, options);
	}

	/**
	 * Resume a previous session with a new prompt.
	 * @param sessionId the session ID to resume
	 * @param prompt the new prompt/goal to execute
	 * @param options execution options
	 * @return the execution result
	 * @throws CodexSDKException if execution fails
	 */
	public ExecuteResult resume(String sessionId, String prompt, ExecuteOptions options) {
		if (sessionId == null || sessionId.isEmpty()) {
			throw new IllegalArgumentException("Session ID cannot be null or empty");
		}
		if (prompt == null || prompt.isEmpty()) {
			throw new IllegalArgumentException("Prompt cannot be null or empty");
		}
		if (options == null) {
			options = ExecuteOptions.defaultOptions();
		}

		List<String> command = buildCommand(codexCliPath, prompt, options, sessionId);
		return executeCommand(command, options);
	}

	/**
	 * Streams JSON lines for one conversation turn on the calling thread. A null thread
	 * ID opens a conversation; otherwise only that exact thread is resumed. Interruption,
	 * timeout and observer failure cancel the zt-exec future and stop its direct child.
	 * @param prompt prompt text
	 * @param options fixed invocation configuration
	 * @param sessionId exact provider thread ID, or null for the first turn
	 * @param observer synchronous line observer
	 * @return child exit code
	 */
	public int stream(String prompt, ExecuteOptions options, String sessionId,
			java.util.function.Consumer<String> observer) {
		var lines = new java.util.concurrent.LinkedBlockingQueue<String>();
		java.util.concurrent.Future<ProcessResult> future = null;
		Process child = null;
		try {
			var process = new ProcessExecutor().command(buildCommand(codexCliPath, prompt, options, sessionId))
				.directory(workingDirectory.toFile())
				.environment(options.getEnvironment())
				// zt-exec's default launch logger prints the environment, including
				// secrets.
				.setMessageLogger(org.zeroturnaround.exec.MessageLoggers.NOP)
				.redirectInput(new java.io.ByteArrayInputStream(new byte[0]))
				.redirectOutput(new org.zeroturnaround.exec.stream.LogOutputStream() {
					@Override
					protected void processLine(String line) {
						lines.add(line);
					}
				})
				.destroyOnExit()
				.start();
			child = process.getProcess();
			future = process.getFuture();
			long deadline = System.nanoTime() + options.getTimeout().toNanos();
			while (!future.isDone() || !lines.isEmpty()) {
				if (System.nanoTime() >= deadline) {
					throw new CodexSDKException("Codex conversation turn timed out");
				}
				String line = lines.poll(25, TimeUnit.MILLISECONDS);
				if (line != null) {
					line = redact(line, options);
					observer.accept(line);
				}
			}
			return future.get().getExitValue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CodexSDKException("Codex conversation turn interrupted");
		}
		catch (IOException | java.util.concurrent.ExecutionException ex) {
			// Process exceptions may include the environment. Do not expose their
			// messages.
			throw new CodexSDKException("Codex CLI could not execute the conversation configuration");
		}
		finally {
			// The child can emit output before zt-exec's waiting task starts. Cancelling
			// that task before it starts would otherwise skip its cleanup entirely.
			if (child != null && child.isAlive()) {
				child.destroy();
			}
			if (future != null && !future.isDone()) {
				future.cancel(true);
			}
		}
	}

	private static String redact(String text, ExecuteOptions options) {
		for (String value : options.getEnvironment().values()) {
			if (!value.isEmpty()) {
				text = text.replace(value, "[REDACTED]");
			}
		}
		return text;
	}

	private ExecuteResult executeCommand(List<String> command, ExecuteOptions options) {
		Instant startTime = Instant.now();
		logger.debug("Executing Codex CLI command: {}", redact(String.join(" ", command), options));

		try {
			ProcessResult result = new ProcessExecutor().command(command)
				.directory(workingDirectory.toFile())
				.environment(options.getEnvironment())
				.setMessageLogger(org.zeroturnaround.exec.MessageLoggers.NOP)
				.timeout(options.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.redirectInput(new java.io.ByteArrayInputStream(new byte[0]))
				.destroyOnExit()
				.execute();

			Duration duration = Duration.between(startTime, Instant.now());

			String combinedOutput = redact(result.outputUTF8(), options);
			int exitCode = result.getExitValue();

			logger.debug("Codex CLI execution completed. Exit code: {}, Duration: {}ms", exitCode, duration.toMillis());

			CodexOutput output = CodexOutput.parse(combinedOutput);
			String finalMessage = output.message();
			String activityLog = output.activityLog();
			String model = output.model();

			ExecuteResult execution = new ExecuteResult(finalMessage, activityLog, exitCode, duration, model);
			List<String> rolloutLines = harvestRollout(execution, options, startTime);

			return new ExecuteResult(finalMessage, activityLog, exitCode, duration, model, rolloutLines);
		}
		catch (InterruptedException e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Codex CLI execution interrupted after {}ms", duration.toMillis());
			Thread.currentThread().interrupt();
			throw new CodexSDKException("Codex CLI execution interrupted", e);
		}
		catch (Exception e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Codex CLI execution failed after {}ms", duration.toMillis());
			if (!options.getEnvironment().isEmpty()) {
				throw new CodexSDKException("Failed to execute Codex CLI command with scoped environment");
			}
			throw new CodexSDKException("Failed to execute Codex CLI command", e);
		}
	}

	// Static and package-private for CLI-flag validation tests (mirrors
	// ClaudeAgentModel.buildCLIOptions): command construction is testable without a
	// functional Codex CLI on the machine
	static List<String> buildCommand(String codexCliPath, String prompt, ExecuteOptions options, String sessionId) {
		return CodexCommandBuilder.build(codexCliPath, prompt, options, sessionId);
	}

	private List<String> harvestRollout(ExecuteResult execution, ExecuteOptions options, Instant startTime) {
		CodexRolloutHarvester harvester = new CodexRolloutHarvester(CodexRolloutHarvester.defaultSessionsRoot(),
				ROLLOUT_WAIT, ROLLOUT_POLL_INTERVAL);
		Path effectiveWorkingDirectory = options.getWorkingDirectory() != null ? options.getWorkingDirectory()
				: workingDirectory;
		List<String> rolloutLines = harvester.harvest(execution.getSessionId(), effectiveWorkingDirectory, startTime);
		if (rolloutLines.isEmpty()) {
			logger.warn("No flushed Codex rollout found for session {} in {}", execution.getSessionId(),
					effectiveWorkingDirectory);
		}
		return rolloutLines;
	}

	/**
	 * Checks if the Codex CLI is available and functional.
	 * @return true if available
	 */
	public boolean checkAvailability() {
		return CodexCliDiscovery.validateCodexCli(codexCliPath);
	}

	public String getCodexCliPath() {
		return codexCliPath;
	}

}
