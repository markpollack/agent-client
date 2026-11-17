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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudeArtifactVersionTest {

	private static final String CLASS = "io/github/markpollack/agents/claude/ClaudeArtifactVersion.class";

	private static final String METADATA = "META-INF/maven/io.github.markpollack/agent-claude/pom.properties";

	private static final String SDK_METADATA = "META-INF/maven/io.github.markpollack/claude-code-sdk/pom.properties";

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

	@Test
	void theSdkVersionIsTheLoadedSdkJars() {
		String loaded = CLIOptions.class.getPackage().getImplementationVersion();
		assertThat(loaded).isNotBlank();
		assertThat(ClaudeArtifactVersion.sdkVersion()).isEqualTo(loaded);
	}

	@Test
	void anSdkVersionComesFromTheSdksMavenMetadata() throws Exception {
		var jar = jar(Map.of(SDK_METADATA, "groupId=io.github.markpollack\nartifactId=claude-code-sdk\nversion=1.8.0"));
		assertThat(loadSdk(jar)).isEqualTo("1.8.0");
	}

	@Test
	void withoutMavenMetadataTheSdkVersionComesFromItsManifest() throws Exception {
		var jar = jar(Map.of("META-INF/MANIFEST.MF", manifest("claude-code-sdk", "1.8.0")));
		assertThat(loadSdk(jar)).isEqualTo("1.8.0");
	}

	@ParameterizedTest
	@ValueSource(strings = { "other-artifact|1.8.0", "claude-code-sdk|   ", "claude-code-sdk|" })
	void aManifestForAnotherArtifactOrWithoutAVersionIsUnknown(String titleAndVersion) throws Exception {
		String[] parts = titleAndVersion.split("\\|", -1);
		var jar = jar(Map.of("META-INF/MANIFEST.MF", manifest(parts[0], parts[1])));
		assertThat(loadSdk(jar)).isEqualTo("unknown");
	}

	@Test
	void anSdkJarWithNoMetadataOrManifestIsUnknown() throws Exception {
		assertThat(loadSdk(jar(Map.of()))).isEqualTo("unknown");
	}

	@Test
	void theProducersOwnMetadataIsNotTheSdks() throws Exception {
		var jar = jar(Map.of(METADATA, "groupId=io.github.markpollack\nartifactId=agent-claude\nversion=0.32.1"));
		assertThat(loadSdk(jar)).isEqualTo("unknown");
	}

	private static String manifest(String title, String version) {
		var text = new StringBuilder(Attributes.Name.MANIFEST_VERSION + ": 1.0\n");
		text.append(Attributes.Name.IMPLEMENTATION_TITLE).append(": ").append(title).append('\n');
		if (!version.isEmpty())
			text.append(Attributes.Name.IMPLEMENTATION_VERSION).append(": ").append(version).append('\n');
		return text.toString();
	}

	/** A jar holding this class, as the anchor, and the given text entries. */
	private Path jar(Map<String, String> entries) throws Exception {
		byte[] bytes;
		try (var input = Objects.requireNonNull(ClaudeArtifactVersion.class.getResourceAsStream("/" + CLASS))) {
			bytes = input.readAllBytes();
		}
		var all = new java.util.LinkedHashMap<String, byte[]>();
		all.put(CLASS, bytes);
		entries.forEach((name, text) -> all.put(name, text.getBytes(StandardCharsets.UTF_8)));
		Path jar = temp.resolve("anchor.jar");
		writeJar(jar, all);
		return jar;
	}

	/** {@code versionOf(ClaudeArtifactVersion, "claude-code-sdk")} with the class loaded from {@code jar} alone. */
	private static String loadSdk(Path jar) throws Exception {
		try (var loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null)) {
			var type = Class.forName("io.github.markpollack.agents.claude.ClaudeArtifactVersion", true, loader);
			var method = type.getDeclaredMethod("versionOf", Class.class, String.class);
			method.setAccessible(true);
			return (String) method.invoke(null, type, "claude-code-sdk");
		}
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
