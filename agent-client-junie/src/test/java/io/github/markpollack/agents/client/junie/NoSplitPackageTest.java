/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.markpollack.agents.junie.JunieAgentModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This module and {@code agent-junie} share no package: a split package breaks the
 * Java module system, and in Quarkus dev or test mode the two jars can load in different
 * class loaders, where package-private access fails.
 */
class NoSplitPackageTest {

	@Test
	void sharesNoPackageWithTheLegacyProvider() throws Exception {
		Set<String> own = packages(Path.of("target", "classes"));
		Set<String> legacy = packages(location(JunieAgentModel.class));
		assertThat(own).isNotEmpty();
		assertThat(legacy).isNotEmpty();
		assertThat(own).doesNotContainAnyElementsOf(legacy);
	}

	private static Path location(Class<?> type) throws URISyntaxException {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
	}

	/** The packages that hold classes under a classes directory or jar. */
	private static Set<String> packages(Path root) throws IOException {
		if (Files.isRegularFile(root)) {
			try (var jar = FileSystems.newFileSystem(root)) {
				return packages(jar.getPath("/"));
			}
		}
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(file -> file.toString().endsWith(".class"))
				.map(file -> root.relativize(file.getParent()).toString().replace('/', '.'))
				.filter(pkg -> !pkg.isEmpty() && !pkg.startsWith("META-INF"))
				.collect(Collectors.toSet());
		}
	}

}
