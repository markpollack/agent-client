/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No file under any module's {@code src/main} names a {@code -SNAPSHOT} version. A
 * version written into shipped code is wrong the moment the release is cut: provenance
 * such as a recording's SDK version must come from the loaded artifact instead.
 */
class NoSnapshotInShippedSourcesTest {

	@Test
	void noShippedSourceNamesASnapshotVersion() throws IOException {
		Path root = Path.of("..").toRealPath();
		assertThat(root.resolve("pom.xml")).as("runs from a module of the Agent Client reactor").exists();
		List<Path> files = shippedFiles(root);
		assertThat(files).as("shipped sources found under %s", root).hasSizeGreaterThan(300);
		List<String> offenders = new ArrayList<>();
		for (Path file : files)
			// ISO-8859-1 maps every byte to one char, so binary resources are scanned too.
			if (new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("-SNAPSHOT"))
				offenders.add(root.relativize(file).toString());
		assertThat(offenders).as("files under src/main that contain -SNAPSHOT").isEmpty();
	}

	private static List<Path> shippedFiles(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile).filter(path -> {
				String relative = root.relativize(path).toString().replace('\\', '/');
				return relative.contains("/src/main/") && !relative.contains("/target/")
						&& !relative.contains("/node_modules/") && !relative.startsWith(".git/");
			}).toList();
		}
	}

}
