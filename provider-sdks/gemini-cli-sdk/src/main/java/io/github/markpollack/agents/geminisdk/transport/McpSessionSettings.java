/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run's MCP servers for the Gemini CLI, kept out of the workspace. The servers go in
 * an owner-only {@code settings.json} in a new private temporary directory. The CLI reads
 * it as its system settings through {@value #SYSTEM_SETTINGS_ENV}, and
 * {@code mcp.allowed} names only these servers. {@link #close()} removes the directory.
 *
 * <p>
 * The variable replaces the system settings file an administrator may have installed, so
 * opening refuses when one exists or when the variable is already set, rather than
 * silently bypassing that policy.
 */
public final class McpSessionSettings implements AutoCloseable {

	/** The Gemini CLI's override for its system settings file. */
	public static final String SYSTEM_SETTINGS_ENV = "GEMINI_CLI_SYSTEM_SETTINGS_PATH";

	private static final Logger logger = LoggerFactory.getLogger(McpSessionSettings.class);

	private static final McpSessionSettings NONE = new McpSessionSettings(null);

	private final Path directory;

	private McpSessionSettings(Path directory) {
		this.directory = directory;
	}

	/**
	 * Writes the servers to a new session settings file, or does nothing when there are
	 * none.
	 * @param mcpServers server name to Gemini MCP configuration
	 * @return the session settings; close it after the CLI exits
	 * @throws IllegalStateException when an administrator's system settings would be
	 * replaced
	 * @throws IOException when the file cannot be written
	 */
	public static McpSessionSettings open(Map<String, Object> mcpServers) throws IOException {
		return open(mcpServers, defaultSystemSettingsPath(), System.getenv(SYSTEM_SETTINGS_ENV));
	}

	static McpSessionSettings open(Map<String, Object> mcpServers, Path adminSettings, String configuredOverride)
			throws IOException {
		if (mcpServers == null || mcpServers.isEmpty()) {
			return NONE;
		}
		refuseToReplaceSystemSettings(mcpServers, adminSettings, configuredOverride);
		Path directory = createPrivateDirectory();
		try {
			Map<String, Object> settings = new LinkedHashMap<>();
			settings.put("mcpServers", mcpServers);
			settings.put("mcp", Map.of("allowed", new ArrayList<>(mcpServers.keySet())));
			Path file = directory.resolve("settings.json");
			Files.createFile(file);
			restrictToOwner(file, "rw-------");
			new ObjectMapper().writeValue(file.toFile(), settings);
			return new McpSessionSettings(directory);
		}
		catch (IOException | RuntimeException ex) {
			new McpSessionSettings(directory).close();
			throw ex;
		}
	}

	private static void refuseToReplaceSystemSettings(Map<String, Object> mcpServers, Path adminSettings,
			String configuredOverride) {
		if (configuredOverride != null && !configuredOverride.isBlank()) {
			throw new IllegalStateException(SYSTEM_SETTINGS_ENV + " is already set to " + configuredOverride
					+ "; Client will not replace it to pass MCP servers " + mcpServers.keySet());
		}
		if (adminSettings != null && Files.exists(adminSettings)) {
			throw new IllegalStateException("Gemini system settings exist at " + adminSettings
					+ "; Client will not bypass them to pass MCP servers " + mcpServers.keySet());
		}
	}

	/**
	 * The environment the CLI needs to read these settings.
	 * @return {@value #SYSTEM_SETTINGS_ENV} and the file, or empty when there are no
	 * servers
	 */
	public Map<String, String> environment() {
		return (this.directory != null) ? Map.of(SYSTEM_SETTINGS_ENV, this.directory.resolve("settings.json").toString())
				: Map.of();
	}

	@Override
	public void close() {
		if (this.directory == null) {
			return;
		}
		try {
			Files.deleteIfExists(this.directory.resolve("settings.json"));
			Files.deleteIfExists(this.directory);
		}
		catch (IOException ex) {
			logger.warn("Failed to remove Gemini MCP session settings {}", this.directory, ex);
		}
	}

	static Path defaultSystemSettingsPath() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac")) {
			return Path.of("/Library/Application Support/GeminiCli/settings.json");
		}
		if (os.contains("win")) {
			return Path.of("C:\\ProgramData\\gemini-cli\\settings.json");
		}
		return Path.of("/etc/gemini-cli/settings.json");
	}

	private static Path createPrivateDirectory() throws IOException {
		Path directory = Files.createTempDirectory("gemini-mcp-");
		restrictToOwner(directory, "rwx------");
		return directory;
	}

	private static void restrictToOwner(Path path, String permissions) throws IOException {
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
		}
	}

}
