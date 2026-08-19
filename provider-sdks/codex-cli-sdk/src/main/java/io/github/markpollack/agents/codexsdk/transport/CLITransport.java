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
import java.util.ArrayList;
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

	private ExecuteResult executeCommand(List<String> command, ExecuteOptions options) {
		Instant startTime = Instant.now();
		logger.debug("Executing Codex CLI command: {}", String.join(" ", command));

		try {
			ProcessResult result = new ProcessExecutor().command(command)
				.directory(workingDirectory.toFile())
				.timeout(options.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.redirectInput(new java.io.ByteArrayInputStream(new byte[0]))
				.destroyOnExit()
				.execute();

			Duration duration = Duration.between(startTime, Instant.now());

			String combinedOutput = result.outputUTF8();
			int exitCode = result.getExitValue();

			logger.debug("Codex CLI execution completed. Exit code: {}, Duration: {}ms", exitCode, duration.toMillis());

			// Codex outputs activity to stderr and final message to stdout
			// Since zt-exec combines them by default, we parse from combined output
			// The response appears between the "codex" marker and "tokens used" marker
			String[] lines = combinedOutput.split("\n");

			// Find the last "codex" marker and "tokens used" marker
			int codexMarker = -1;
			int tokensMarker = -1;
			for (int i = lines.length - 1; i >= 0; i--) {
				if (tokensMarker < 0 && lines[i].trim().equals("tokens used")) {
					tokensMarker = i;
				}
				if (codexMarker < 0 && lines[i].trim().equals("codex")) {
					codexMarker = i;
				}
				if (codexMarker >= 0 && tokensMarker >= 0) {
					break;
				}
			}

			String activityLog = combinedOutput;
			String finalMessage = "";
			if (codexMarker >= 0) {
				// Extract text between "codex" marker and "tokens used" (or end of
				// output)
				int end = tokensMarker > codexMarker ? tokensMarker : lines.length;
				if (codexMarker + 1 < end) {
					finalMessage = String.join("\n", java.util.Arrays.copyOfRange(lines, codexMarker + 1, end));
				}
			}
			else if (tokensMarker >= 0 && tokensMarker > 0) {
				// Fallback: no "codex" marker, take everything before "tokens used"
				finalMessage = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, tokensMarker));
			}
			else {
				// No markers found — treat entire output as the message
				finalMessage = combinedOutput;
			}

			// Extract model from activity log
			String model = extractModel(activityLog);

			return new ExecuteResult(finalMessage, activityLog, exitCode, duration, model);
		}
		catch (IOException e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Codex CLI execution failed after {}ms: {}", duration.toMillis(), e.getMessage());
			throw new CodexSDKException("Failed to execute Codex CLI command", e);
		}
		catch (InterruptedException e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Codex CLI execution interrupted after {}ms", duration.toMillis());
			Thread.currentThread().interrupt();
			throw new CodexSDKException("Codex CLI execution interrupted", e);
		}
		catch (Exception e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Codex CLI execution failed after {}ms: {}", duration.toMillis(), e.getMessage());
			throw new CodexSDKException("Failed to execute Codex CLI command", e);
		}
	}

	// Static and package-private for CLI-flag validation tests (mirrors
	// ClaudeAgentModel.buildCLIOptions): command construction is testable without a
	// functional Codex CLI on the machine
	static List<String> buildCommand(String codexCliPath, String prompt, ExecuteOptions options, String sessionId) {
		List<String> command = new ArrayList<>();

		// Base command + exec subcommand
		command.add(codexCliPath);
		command.add("exec");

		// Model is an exec-specific option (not a global flag)
		if (options.getModel() != null && !options.getModel().isEmpty()) {
			command.add("--model");
			command.add(options.getModel());
		}

		// Reasoning effort: no dedicated flag — uses the -c config override. The value
		// is quoted so it parses as a TOML string.
		if (options.getReasoningEffort() != null && !options.getReasoningEffort().isEmpty()) {
			command.add("-c");
			command.add("model_reasoning_effort=\"" + options.getReasoningEffort() + "\"");
		}

		// Execution mode
		if (options.isDangerouslyBypassSandbox()) {
			command.add("--dangerously-bypass-approvals-and-sandbox");
		}
		else if (options.isFullAuto()) {
			command.add("--full-auto");
		}
		else {
			command.add("--sandbox");
			command.add(options.getSandboxMode().getValue());
		}

		// Working directory via -C flag
		if (options.getWorkingDirectory() != null) {
			command.add("-C");
			command.add(options.getWorkingDirectory().toString());
		}

		// Git check skip
		if (options.isSkipGitCheck()) {
			command.add("--skip-git-repo-check");
		}

		// JSON output mode
		if (options.isJsonOutput()) {
			command.add("--json");
		}

		// Output schema for structured output
		if (options.getOutputSchema() != null) {
			command.add("--output-schema");
			command.add(options.getOutputSchema().toString());
		}

		// Session resume
		if (sessionId != null && !sessionId.isEmpty()) {
			command.add("resume");
			command.add(sessionId);
		}

		// End of flags, prompt as final argument
		command.add("--");
		command.add(prompt);

		return command;
	}

	/**
	 * Extracts model name from Codex stderr output. Model appears in stderr like: "model:
	 * gpt-5-codex"
	 * @param stderr the stderr output
	 * @return extracted model name or "codex-default" if not found
	 */
	private String extractModel(String stderr) {
		if (stderr == null || stderr.isEmpty()) {
			return "codex-default";
		}

		// Look for "model: <model-name>" in stderr
		String[] lines = stderr.split("\n");
		for (String line : lines) {
			if (line.startsWith("model:")) {
				return line.substring("model:".length()).trim();
			}
		}

		return "codex-default";
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
