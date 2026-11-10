/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.ampsdk.exceptions.AmpSDKException;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;
import io.github.markpollack.agents.ampsdk.types.ExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Low-level transport layer for Amp CLI communication using zt-exec for robust process
 * management.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class CLITransport {

	private static final Logger logger = LoggerFactory.getLogger(CLITransport.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Path workingDirectory;

	private final String ampCliPath;

	public CLITransport(Path workingDirectory) {
		this(workingDirectory, null);
	}

	public CLITransport(Path workingDirectory, String ampCliPath) {
		this.workingDirectory = workingDirectory;
		this.ampCliPath = ampCliPath != null ? ampCliPath : AmpCliDiscovery.discoverAmpCli();

		// Validate the CLI is functional
		if (!AmpCliDiscovery.validateAmpCli(this.ampCliPath)) {
			throw new AmpSDKException("Amp CLI at " + this.ampCliPath + " is not functional");
		}

		logger.info("Amp CLI initialized at: {}", this.ampCliPath);
	}

	/**
	 * Execute a prompt via Amp CLI in execute mode.
	 * @param prompt the user prompt/goal to execute
	 * @param options execution options
	 * @return the execution result
	 * @throws AmpSDKException if execution fails
	 */
	public ExecuteResult execute(String prompt, ExecuteOptions options) {
		if (prompt == null || prompt.isEmpty()) {
			throw new IllegalArgumentException("Prompt cannot be null or empty");
		}
		if (options == null) {
			options = ExecuteOptions.defaultOptions();
		}

		Instant startTime = Instant.now();
		Path mcpConfig = null;

		try {
			// MCP server configuration routinely holds tokens, so it goes in an
			// owner-only file rather than on the command line.
			mcpConfig = writeMcpConfig(options.getMcpServers());
			List<String> command = buildCommand(ampCliPath, options, mcpConfig);
			logger.debug("Executing Amp CLI command: {}", command);

			// Amp CLI requires prompt via stdin when using -x flag
			ProcessResult result = new ProcessExecutor().command(command)
				.directory(workingDirectory.toFile())
				.timeout(options.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.destroyOnExit()
				.redirectInput(
						new java.io.ByteArrayInputStream(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.execute();

			Duration duration = Duration.between(startTime, Instant.now());

			String output = result.outputUTF8();
			int exitCode = result.getExitValue();

			logger.debug("Amp CLI execution completed. Exit code: {}, Duration: {}ms", exitCode, duration.toMillis());

			// Extract model from output if available (Amp doesn't return model in simple
			// mode)
			String model = "amp-default";

			return new ExecuteResult(output, exitCode, duration, model);
		}
		catch (Exception e) {
			Duration duration = Duration.between(startTime, Instant.now());
			logger.error("Amp CLI execution failed after {}ms: {}", duration.toMillis(), e.getMessage());
			throw new AmpSDKException("Failed to execute Amp CLI command", e);
		}
		finally {
			deleteMcpConfig(mcpConfig);
		}
	}

	private static Path writeMcpConfig(Map<String, Object> mcpServers) throws IOException {
		if (mcpServers.isEmpty()) {
			return null;
		}
		Path file = Files.createTempFile("amp-mcp-", ".json");
		try {
			if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			}
			Files.writeString(file, MAPPER.writeValueAsString(mcpServers));
			return file;
		}
		catch (IOException | RuntimeException ex) {
			deleteMcpConfig(file);
			throw ex;
		}
	}

	private static void deleteMcpConfig(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			logger.warn("Failed to delete Amp MCP config file {}", file, ex);
		}
	}

	static List<String> buildCommand(String ampCliPath, ExecuteOptions options, Path mcpConfig) {
		List<String> command = new ArrayList<>();

		// Base command
		command.add(ampCliPath);

		// Add options BEFORE -x flag
		// --dangerously-allow-all must come before -x
		if (options.isDangerouslyAllowAll()) {
			command.add("--dangerously-allow-all");
		}

		// Add model if specified
		// Note: Amp CLI uses environment variable for model selection, not a flag
		// We'll pass the model via environment in the future if needed

		if (mcpConfig != null) {
			command.add("--mcp-config");
			command.add(mcpConfig.toString());
		}

		// Execute mode flag (prompt will be provided via stdin)
		command.add("-x");

		return command;
	}

	/**
	 * Checks if the Amp CLI is available and functional.
	 * @return true if available
	 */
	public boolean checkAvailability() {
		return AmpCliDiscovery.validateAmpCli(ampCliPath);
	}

	public String getAmpCliPath() {
		return ampCliPath;
	}

}
