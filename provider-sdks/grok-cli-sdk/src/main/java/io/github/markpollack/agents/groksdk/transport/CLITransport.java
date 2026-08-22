/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.transport;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.groksdk.exceptions.GrokSDKException;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.ExecuteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

/**
 * Runs the Grok CLI as a subprocess.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class CLITransport {

	private static final Logger logger = LoggerFactory.getLogger(CLITransport.class);

	private final String grokCliPath;

	public CLITransport() {
		this(null);
	}

	public CLITransport(String grokCliPath) {
		String resolved = (grokCliPath != null && !grokCliPath.isEmpty()) ? grokCliPath
				: GrokCliDiscovery.discoverGrokCli();
		if (!GrokCliDiscovery.validateGrokCli(resolved)) {
			throw new GrokSDKException("Grok CLI at " + resolved + " is not functional");
		}
		this.grokCliPath = resolved;
	}

	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		return execute(prompt, options, null);
	}

	/**
	 * Execute a prompt, optionally resuming an existing session.
	 * @param prompt the prompt to send
	 * @param options execution options
	 * @param sessionId session to resume, or null for a new session
	 * @return the parsed result
	 */
	public ExecuteResult execute(String prompt, ExecuteOptions options, String sessionId) {
		List<String> command = buildCommand(this.grokCliPath, prompt, options, sessionId);
		logger.debug("Executing Grok CLI: {}", command);

		long started = System.currentTimeMillis();
		try {
			ProcessExecutor executor = new ProcessExecutor().command(command)
				.readOutput(true)
				.timeout(options.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				// Never let the CLI block waiting on a stdin that is not coming.
				.redirectInput(new ByteArrayInputStream(new byte[0]))
				.destroyOnExit();
			if (options.getWorkingDirectory() != null) {
				executor.directory(options.getWorkingDirectory().toFile());
			}
			ProcessResult result = executor.execute();
			Duration duration = Duration.ofMillis(System.currentTimeMillis() - started);
			return ExecuteResult.parseStreaming(result.outputUTF8(), result.getExitValue(), duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GrokSDKException("Interrupted while executing Grok CLI", ex);
		}
		catch (Exception ex) {
			throw new GrokSDKException("Grok CLI execution failed: " + ex.getMessage(), ex);
		}
	}

	public boolean isAvailable() {
		return GrokCliDiscovery.validateGrokCli(this.grokCliPath);
	}

	public String getGrokCliPath() {
		return this.grokCliPath;
	}

	/**
	 * Build the argv.
	 *
	 * <p>
	 * Static and package-private so the flag mapping can be asserted without a Grok CLI
	 * on the machine — the same reason the Codex SDK does it. SDK flag mappings drift
	 * silently as CLIs evolve; asserting the command line is what catches that.
	 *
	 * <p>
	 * Two things are worth knowing about the shape:
	 * <ul>
	 * <li>Resume is {@code --resume <id>} on an otherwise ordinary invocation, and a new
	 * session can be given its id up front with {@code --session-id}. Grok is the only
	 * CLI in this family that lets the caller choose the session identifier, so nothing
	 * has to be scraped back out of a log.</li>
	 * <li>{@code --verbatim} is set so the prompt is sent exactly as written. Without it
	 * the CLI may reinterpret the prompt, which would mean two providers were not given
	 * the same instruction.</li>
	 * </ul>
	 * @param grokCliPath resolved CLI path
	 * @param prompt the prompt
	 * @param options execution options
	 * @param sessionId session to resume, or null
	 * @return the argv
	 */
	static List<String> buildCommand(String grokCliPath, String prompt, ExecuteOptions options, String sessionId) {
		List<String> command = new ArrayList<>();
		command.add(grokCliPath);

		if (options.getModel() != null && !options.getModel().isEmpty()) {
			command.add("--model");
			command.add(options.getModel());
		}
		if (options.getReasoningEffort() != null && !options.getReasoningEffort().isEmpty()) {
			command.add("--reasoning-effort");
			command.add(options.getReasoningEffort());
		}
		if (options.getPermissionMode() != null) {
			command.add("--permission-mode");
			command.add(options.getPermissionMode().getValue());
		}
		if (options.getMaxTurns() != null) {
			command.add("--max-turns");
			command.add(String.valueOf(options.getMaxTurns()));
		}
		if (options.getSystemPromptOverride() != null && !options.getSystemPromptOverride().isEmpty()) {
			command.add("--system-prompt-override");
			command.add(options.getSystemPromptOverride());
		}
		if (options.getJsonSchema() != null && !options.getJsonSchema().isEmpty()) {
			// --json-schema implies --output-format json, but the format is still passed
			// explicitly below so the argv says what it means.
			command.add("--json-schema");
			command.add(options.getJsonSchema());
		}
		if (!options.getAllowedTools().isEmpty()) {
			command.add("--tools");
			command.add(String.join(",", options.getAllowedTools()));
		}
		for (String denied : options.getDisallowedTools()) {
			command.add("--deny");
			command.add(denied);
		}
		if (options.isDisableWebSearch()) {
			command.add("--disable-web-search");
		}
		if (options.getWorkingDirectory() != null) {
			command.add("--cwd");
			command.add(options.getWorkingDirectory().toString());
		}
		if (sessionId != null && !sessionId.isEmpty()) {
			command.add("--resume");
			command.add(sessionId);
		}

		command.add("--output-format");
		command.add("streaming-json");
		command.add("--verbatim");
		command.add("--single");
		command.add(prompt);
		return command;
	}

}
