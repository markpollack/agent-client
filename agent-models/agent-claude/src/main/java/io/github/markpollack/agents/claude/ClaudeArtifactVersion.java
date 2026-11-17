/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.net.JarURLConnection;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/**
 * Version metadata from an artifact's own jar, never another classpath entry: this
 * producer's ({@code agent-claude}) and the Claude Agent SDK's ({@code claude-code-sdk}).
 */
final class ClaudeArtifactVersion {

	private static final String GROUP = "io.github.markpollack";

	private ClaudeArtifactVersion() {
	}

	/** This producer's version, from {@code agent-claude}'s own Maven metadata. */
	static String version() {
		return versionOf(ClaudeArtifactVersion.class, "agent-claude");
	}

	/**
	 * The loaded Claude Agent SDK's version: from the Maven metadata of the jar that holds
	 * the SDK's classes, or that jar's manifest when it has none.
	 */
	static String sdkVersion() {
		return versionOf(CLIOptions.class, "claude-code-sdk");
	}

	/**
	 * The version of {@code artifactId} read from the jar that loaded {@code anchor}, or
	 * {@code unknown} when that jar names another artifact or no version.
	 */
	static String versionOf(Class<?> anchor, String artifactId) {
		var resource = anchor.getResource(anchor.getSimpleName() + ".class");
		if (resource == null) {
			return "unknown";
		}
		try {
			if (!(resource.openConnection() instanceof JarURLConnection connection)) {
				return "unknown";
			}
			connection.setUseCaches(false);
			try (var jar = connection.getJarFile()) {
				var entry = jar.getJarEntry("META-INF/maven/" + GROUP + "/" + artifactId + "/pom.properties");
				if (entry == null) {
					return manifestVersion(jar.getManifest(), artifactId);
				}
				var properties = new Properties();
				try (var input = jar.getInputStream(entry)) {
					properties.load(input);
				}
				return validatedVersion(properties, artifactId);
			}
		}
		catch (IOException | IllegalArgumentException | SecurityException ex) {
			return "unknown";
		}
	}

	private static String manifestVersion(Manifest manifest, String artifactId) {
		if (manifest == null) {
			return "unknown";
		}
		Attributes main = manifest.getMainAttributes();
		if (!artifactId.equals(main.getValue(Attributes.Name.IMPLEMENTATION_TITLE))) {
			return "unknown";
		}
		String version = main.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
		return version == null || version.isBlank() ? "unknown" : version.trim();
	}

	private static String validatedVersion(Properties properties, String artifactId) {
		if (!GROUP.equals(properties.getProperty("groupId"))
				|| !artifactId.equals(properties.getProperty("artifactId"))) {
			return "unknown";
		}
		String version = properties.getProperty("version", "").trim();
		return version.isEmpty() ? "unknown" : version;
	}

}
