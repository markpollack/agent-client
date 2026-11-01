/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.net.JarURLConnection;
import java.util.Properties;

/** Version metadata from this producer's own artifact, never another classpath entry. */
final class ClaudeArtifactVersion {

	private static final String METADATA = "META-INF/maven/io.github.markpollack/agent-claude/pom.properties";

	private ClaudeArtifactVersion() {
	}

	static String version() {
		var resource = ClaudeArtifactVersion.class.getResource("ClaudeArtifactVersion.class");
		if (resource == null) {
			return "unknown";
		}
		try {
			if (!(resource.openConnection() instanceof JarURLConnection connection)) {
				return "unknown";
			}
			connection.setUseCaches(false);
			try (var jar = connection.getJarFile()) {
				var entry = jar.getJarEntry(METADATA);
				if (entry == null) {
					return "unknown";
				}
				var properties = new Properties();
				try (var input = jar.getInputStream(entry)) {
					properties.load(input);
				}
				return validatedVersion(properties);
			}
		}
		catch (IOException | IllegalArgumentException | SecurityException ex) {
			return "unknown";
		}
	}

	private static String validatedVersion(Properties properties) {
		if (!"io.github.markpollack".equals(properties.getProperty("groupId"))
				|| !"agent-claude".equals(properties.getProperty("artifactId"))) {
			return "unknown";
		}
		String version = properties.getProperty("version", "").trim();
		return version.isEmpty() ? "unknown" : version;
	}

}
