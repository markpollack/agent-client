/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudeArtifactVersionTest {

	private static final String CLASS = "io/github/markpollack/agents/claude/ClaudeArtifactVersion.class";

	private static final String METADATA = "META-INF/maven/io.github.markpollack/agent-claude/pom.properties";

	@TempDir
	Path temp;

	@Test
	void explodedTestClasspathDoesNotInventARelease() {
		assertThat(ClaudeArtifactVersion.version()).isEqualTo("unknown");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.32.1-fresh-v3-provenance-SNAPSHOT", "9.8.7-test" })
	void derivesEachPackagedVersion(String version) throws Exception {
		var jar = producer("groupId=io.github.markpollack\nartifactId=agent-claude\nversion=" + version);
		assertThat(load(jar)).isEqualTo(version);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "groupId=other\nartifactId=agent-claude\nversion=wrong",
			"groupId=io.github.markpollack\nartifactId=other\nversion=wrong",
			"groupId=io.github.markpollack\nartifactId=agent-claude\nversion=   ",
			"groupId=io.github.markpollack\nartifactId=agent-claude\nversion=\\uZZZZ" })
	void missingInvalidOrUnrelatedCoordinatesRemainUnknown(String properties) throws Exception {
		assertThat(load(producer(properties))).isEqualTo("unknown");
	}

	@Test
	void neverBorrowsMetadataFromAnotherJar() throws Exception {
		Path own = producer("");
		Path dependency = temp.resolve("dependency.jar");
		writeJar(dependency,
				Map.of(METADATA, "groupId=io.github.markpollack\nartifactId=agent-claude\nversion=unrelated"
					.getBytes(StandardCharsets.UTF_8)));
		assertThat(load(dependency, own)).isEqualTo("unknown");
	}

	private Path producer(String properties) throws Exception {
		byte[] bytes;
		try (var input = Objects.requireNonNull(ClaudeArtifactVersion.class.getResourceAsStream("/" + CLASS))) {
			bytes = input.readAllBytes();
		}
		Path jar = temp.resolve("producer.jar");
		writeJar(jar, properties.isEmpty() ? Map.of(CLASS, bytes)
				: Map.of(CLASS, bytes, METADATA, properties.getBytes(StandardCharsets.UTF_8)));
		return jar;
	}

	private static void writeJar(Path path, Map<String, byte[]> entries) throws Exception {
		try (var output = new JarOutputStream(Files.newOutputStream(path))) {
			for (var entry : entries.entrySet()) {
				output.putNextEntry(new JarEntry(entry.getKey()));
				output.write(entry.getValue());
				output.closeEntry();
			}
		}
	}

	private static String load(Path... paths) throws Exception {
		URL[] urls = new URL[paths.length];
		for (int i = 0; i < paths.length; i++) {
			urls[i] = paths[i].toUri().toURL();
		}
		try (var loader = new URLClassLoader(urls, null)) {
			var type = Class.forName("io.github.markpollack.agents.claude.ClaudeArtifactVersion", true, loader);
			var method = type.getDeclaredMethod("version");
			method.setAccessible(true);
			return (String) method.invoke(null);
		}
	}

}
