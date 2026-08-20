/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.antigravitysdk.exceptions.AntigravitySDKException;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

/**
 * Runs the Antigravity CLI ({@code agy}) as a subprocess.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class CLITransport {

	private static final Logger logger = LoggerFactory.getLogger(CLITransport.class);

	private final String agyCliPath;

	public CLITransport() {
		this(null);
	}

	public CLITransport(String agyCliPath) {
		String resolved = (agyCliPath != null && !agyCliPath.isEmpty()) ? agyCliPath
				: AntigravityCliDiscovery.discoverAntigravityCli();
		if (!AntigravityCliDiscovery.validateAntigravityCli(resolved)) {
			throw new AntigravitySDKException("Antigravity CLI at " + resolved + " is not functional");
		}
		this.agyCliPath = resolved;
	}

	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		return execute(prompt, options, null);
	}

	/**
	 * Execute a prompt, optionally resuming a conversation.
	 * @param prompt the prompt to send
	 * @param options execution options
	 * @param conversationId conversation to resume, or null for a new one
	 * @return the parsed result
	 */
	public ExecuteResult execute(String prompt, ExecuteOptions options, String conversationId) {
		List<String> command = buildCommand(this.agyCliPath, prompt, options, conversationId);
		logger.debug("Executing Antigravity CLI: {}", command);

		long started = System.currentTimeMillis();
		// stderr is captured SEPARATELY rather than combined into stdout. Two reasons,
		// both
		// load-bearing: stdout must stay clean JSON for the parser, and the only signal
		// that a tool call was refused is a notice on stderr.
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		try {
			ProcessExecutor executor = new ProcessExecutor().command(command)
				.readOutput(true)
				.redirectError(stderr)
				// agy has no --cwd; the process working directory IS the workspace.
				.directory(resolveWorkingDirectory(options).toFile())
				// The zt-exec timeout is the outer bound. --print-timeout below is the
				// CLI's own, and both are set: the CLI's default is five minutes, far
				// short of a real task, and letting it fire first would look like the
				// agent finishing early.
				.timeout(options.getTimeout().toMillis() + TIMEOUT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
				.redirectInput(new ByteArrayInputStream(new byte[0]))
				.destroyOnExit();
			ProcessResult result = executor.execute();
			Duration duration = Duration.ofMillis(System.currentTimeMillis() - started);
			ExecuteResult parsed = ExecuteResult.parse(result.outputUTF8(), stderr.toString(StandardCharsets.UTF_8),
					result.getExitValue(), duration);
			if (parsed.isSoftDenied()) {
				logger.warn("Antigravity refused {} tool call(s) and still reported status={}: {}",
						parsed.getPermissionNotices().size(), parsed.getStatus(), parsed.getPermissionNotices());
			}
			return parsed;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AntigravitySDKException("Interrupted while executing Antigravity CLI", ex);
		}
		catch (Exception ex) {
			throw new AntigravitySDKException("Antigravity CLI execution failed: " + ex.getMessage(), ex);
		}
	}

	/** Slack between the CLI's own timeout and ours, so the CLI reports first. */
	private static final long TIMEOUT_GRACE_MILLIS = 30_000L;

	private static Path resolveWorkingDirectory(ExecuteOptions options) {
		return (options.getWorkingDirectory() != null) ? options.getWorkingDirectory()
				: Path.of(System.getProperty("user.dir"));
	}

	public boolean isAvailable() {
		return AntigravityCliDiscovery.validateAntigravityCli(this.agyCliPath);
	}

	public String getAgyCliPath() {
		return this.agyCliPath;
	}

	/**
	 * Build the argv.
	 *
	 * <p>
	 * Static and package-private so the flag mapping can be asserted without an
	 * {@code agy} on the machine. Three things about this CLI's shape are easy to get
	 * wrong:
	 *
	 * <ul>
	 * <li><b>There is no {@code --cwd}.</b> The workspace is the process working
	 * directory, set by the transport; {@code --add-dir} only widens it. A caller that
	 * expects a flag to move the workspace gets a run against the wrong tree.</li>
	 * <li><b>{@code --print-timeout} defaults to five minutes</b>, well short of a real
	 * task, so it is always set from the caller's timeout rather than left alone.</li>
	 * <li><b>Permissions default to soft-deny.</b> Without
	 * {@code --dangerously-skip-permissions} or a matching {@code permissions.allow}
	 * entry in settings, tool calls are refused and the run still succeeds.</li>
	 * <li><b>Effort may already be part of the model slug</b>, in which case passing
	 * {@code --effort} is a hard error — see {@link #shouldPassEffort}.</li>
	 * </ul>
	 * @param agyCliPath resolved CLI path
	 * @param prompt the prompt
	 * @param options execution options
	 * @param conversationId conversation to resume, or null
	 * @return the argv
	 */
	static List<String> buildCommand(String agyCliPath, String prompt, ExecuteOptions options, String conversationId) {
		List<String> command = new ArrayList<>();
		command.add(agyCliPath);

		if (options.getModel() != null && !options.getModel().isEmpty()) {
			command.add("--model");
			command.add(options.getModel());
		}
		if (shouldPassEffort(options.getModel(), options.getEffort())) {
			command.add("--effort");
			command.add(options.getEffort());
		}
		if (options.getAgent() != null && !options.getAgent().isEmpty()) {
			command.add("--agent");
			command.add(options.getAgent());
		}
		if (options.getMode() != null) {
			command.add("--mode");
			command.add(options.getMode().getValue());
		}
		if (options.isDangerouslySkipPermissions()) {
			command.add("--dangerously-skip-permissions");
		}
		if (options.isSandbox()) {
			command.add("--sandbox");
		}
		for (Path dir : options.getAdditionalDirectories()) {
			command.add("--add-dir");
			command.add(dir.toString());
		}
		if (options.getJsonSchema() != null && !options.getJsonSchema().isEmpty()) {
			command.add("--json-schema");
			command.add(options.getJsonSchema());
		}
		if (conversationId != null && !conversationId.isEmpty()) {
			command.add("--conversation");
			command.add(conversationId);
		}

		command.add("--print-timeout");
		command.add(formatTimeout(options.getTimeout()));
		command.add("--output-format");
		command.add("json");
		command.add("--print");
		command.add(prompt);
		return command;
	}

	/**
	 * Effort suffixes Antigravity bakes into a model slug, e.g.
	 * {@code gemini-3.1-pro-high}.
	 */
	private static final List<String> EFFORT_SUFFIXES = List.of("-low", "-medium", "-high");

	/**
	 * Whether {@code --effort} may be passed alongside this model.
	 *
	 * <p>
	 * Antigravity encodes effort in the model name rather than only in a flag, and
	 * rejects the combination outright:
	 *
	 * <pre>
	 * invalid model selection (--model "gemini-3.1-pro-high" --effort "low"):
	 *   --model gemini-3.1-pro-high conflicts with --effort=low
	 * </pre>
	 *
	 * <p>
	 * The run fails immediately with {@code "status": "ERROR"} and no output. So when the
	 * slug already carries an effort, the slug wins and the flag is dropped. Portable
	 * {@code low}/{@code medium}/{@code high} then behaves here the way it behaves for
	 * every other provider, instead of requiring the caller to know that this one CLI
	 * names effort twice.
	 * @param model the model slug, may be null
	 * @param effort the requested effort, may be null
	 * @return true when the flag should be added
	 */
	static boolean shouldPassEffort(String model, String effort) {
		if (effort == null || effort.isEmpty()) {
			return false;
		}
		if (model == null || model.isEmpty()) {
			return true;
		}
		String lowered = model.toLowerCase(Locale.ROOT);
		return EFFORT_SUFFIXES.stream().noneMatch(lowered::endsWith);
	}

	/** Go duration syntax, which is what {@code --print-timeout} parses. */
	static String formatTimeout(Duration timeout) {
		long seconds = Math.max(1, timeout.toSeconds());
		return seconds + "s";
	}

}
