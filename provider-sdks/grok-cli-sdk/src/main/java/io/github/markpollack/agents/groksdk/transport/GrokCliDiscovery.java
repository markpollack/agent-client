/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.groksdk.transport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.groksdk.exceptions.GrokSDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

/**
 * Discovers the Grok CLI executable.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class GrokCliDiscovery {

	private static final Logger logger = LoggerFactory.getLogger(GrokCliDiscovery.class);

	private static final String ENV_GROK_CLI_PATH = "GROK_CLI_PATH";

	/**
	 * Discovers the Grok CLI executable path.
	 * @return the path to the Grok CLI executable
	 * @throws GrokSDKException if the CLI cannot be found
	 */
	public static String discoverGrokCli() {
		String envPath = System.getenv(ENV_GROK_CLI_PATH);
		if (envPath != null && !envPath.isEmpty()) {
			Path grokPath = Paths.get(envPath);
			if (Files.exists(grokPath) && Files.isExecutable(grokPath)) {
				logger.info("Found Grok CLI via {} environment variable: {}", ENV_GROK_CLI_PATH, envPath);
				return envPath;
			}
			logger.warn("{} points to a non-existent or non-executable file: {}", ENV_GROK_CLI_PATH, envPath);
		}

		String whichResult = tryWhichCommand();
		if (whichResult != null) {
			logger.info("Found Grok CLI via 'which': {}", whichResult);
			return whichResult;
		}

		String homeDir = System.getProperty("user.home");
		// Grok's installer puts a launcher in ~/.grok/bin and symlinks it into
		// ~/.local/bin.
		String[] commonPaths = { homeDir + "/.local/bin/grok", homeDir + "/.grok/bin/grok", "/usr/local/bin/grok",
				"/usr/bin/grok" };

		for (String path : commonPaths) {
			Path grokPath = Paths.get(path);
			if (Files.exists(grokPath) && Files.isExecutable(grokPath)) {
				logger.info("Found Grok CLI at standard location: {}", path);
				return path;
			}
		}

		throw new GrokSDKException("Grok CLI not found. Install it from https://grok.com/cli, or set "
				+ ENV_GROK_CLI_PATH + " to its location.");
	}

	private static String tryWhichCommand() {
		try {
			ProcessResult result = new ProcessExecutor().command("which", "grok")
				.readOutput(true)
				.timeout(5, TimeUnit.SECONDS)
				.execute();
			if (result.getExitValue() == 0) {
				String path = result.outputUTF8().trim();
				if (!path.isEmpty()) {
					File file = new File(path);
					if (file.exists() && file.canExecute()) {
						return path;
					}
				}
			}
		}
		catch (Exception ex) {
			logger.debug("'which grok' failed: {}", ex.getMessage());
		}
		return null;
	}

	/**
	 * Validates that the Grok CLI is present and runnable.
	 *
	 * <p>
	 * Deliberately checks {@code --version} rather than {@code models}: the
	 * {@code models} subcommand reports "You are not authenticated" even on an
	 * authenticated install, so using it as a health probe would reject a working CLI.
	 * @param grokPath path to the Grok CLI executable
	 * @return true if the CLI is available and functional
	 */
	public static boolean validateGrokCli(String grokPath) {
		try {
			ProcessResult result = new ProcessExecutor().command(grokPath, "--version")
				.readOutput(true)
				.timeout(10, TimeUnit.SECONDS)
				.execute();
			if (result.getExitValue() == 0) {
				logger.debug("Grok CLI version: {}", result.outputUTF8().trim());
				return true;
			}
		}
		catch (Exception ex) {
			logger.warn("Failed to validate Grok CLI at {}: {}", grokPath, ex.getMessage());
		}
		return false;
	}

}
