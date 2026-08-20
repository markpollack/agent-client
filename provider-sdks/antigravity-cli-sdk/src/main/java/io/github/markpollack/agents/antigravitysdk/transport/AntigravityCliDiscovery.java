/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravitysdk.transport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.antigravitysdk.exceptions.AntigravitySDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

/**
 * Discovers the Antigravity CLI executable.
 *
 * <p>
 * The binary is {@code agy}, not {@code antigravity}. Its support files live under
 * {@code ~/.gemini/antigravity-cli}, which it shares with the Gemini CLI, but the
 * executable itself is installed separately — so the config directory existing proves
 * nothing about the CLI being present.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
public class AntigravityCliDiscovery {

	private static final Logger logger = LoggerFactory.getLogger(AntigravityCliDiscovery.class);

	private static final String ENV_ANTIGRAVITY_CLI_PATH = "ANTIGRAVITY_CLI_PATH";

	/**
	 * Discovers the Antigravity CLI executable path.
	 * @return the path to the {@code agy} executable
	 * @throws AntigravitySDKException if the CLI cannot be found
	 */
	public static String discoverAntigravityCli() {
		String envPath = System.getenv(ENV_ANTIGRAVITY_CLI_PATH);
		if (envPath != null && !envPath.isEmpty()) {
			Path agyPath = Paths.get(envPath);
			if (Files.exists(agyPath) && Files.isExecutable(agyPath)) {
				logger.info("Found Antigravity CLI via {} environment variable: {}", ENV_ANTIGRAVITY_CLI_PATH, envPath);
				return envPath;
			}
			logger.warn("{} points to a non-existent or non-executable file: {}", ENV_ANTIGRAVITY_CLI_PATH, envPath);
		}

		String whichResult = tryWhichCommand();
		if (whichResult != null) {
			logger.info("Found Antigravity CLI via 'which': {}", whichResult);
			return whichResult;
		}

		String homeDir = System.getProperty("user.home");
		String[] commonPaths = { homeDir + "/.local/bin/agy", "/usr/local/bin/agy", "/usr/bin/agy" };
		for (String path : commonPaths) {
			Path agyPath = Paths.get(path);
			if (Files.exists(agyPath) && Files.isExecutable(agyPath)) {
				logger.info("Found Antigravity CLI at standard location: {}", path);
				return path;
			}
		}

		throw new AntigravitySDKException("Antigravity CLI (agy) not found. Install it from"
				+ " https://antigravity.google — the npm distribution is discontinued — or set "
				+ ENV_ANTIGRAVITY_CLI_PATH + " to its location.");
	}

	private static String tryWhichCommand() {
		try {
			ProcessResult result = new ProcessExecutor().command("which", "agy")
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
			logger.debug("'which agy' failed: {}", ex.getMessage());
		}
		return null;
	}

	/**
	 * Validates that the Antigravity CLI is present and runnable.
	 *
	 * <p>
	 * This checks the binary, not the credentials. Antigravity authenticates
	 * interactively and caches the result; a headless run without cached credentials
	 * exits with an authentication error rather than hanging, which is where that failure
	 * belongs.
	 * @param agyPath path to the executable
	 * @return true if the CLI is available and functional
	 */
	public static boolean validateAntigravityCli(String agyPath) {
		try {
			ProcessResult result = new ProcessExecutor().command(agyPath, "--version")
				.readOutput(true)
				.timeout(10, TimeUnit.SECONDS)
				.execute();
			if (result.getExitValue() == 0) {
				logger.debug("Antigravity CLI version: {}", result.outputUTF8().trim());
				return true;
			}
		}
		catch (Exception ex) {
			logger.warn("Failed to validate Antigravity CLI at {}: {}", agyPath, ex.getMessage());
		}
		return false;
	}

}
