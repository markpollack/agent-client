/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The Claude provider's offline fixture CLI, copied to a fixed path under {@code target}.
 * Quarkus loads a test class twice, so the path must not depend on the load.
 */
final class Fixture {

	static final Path ROOT = Path.of("target", "quarkus-fixture").toAbsolutePath();

	private Fixture() {
	}

	/**
	 * Prepares a working directory and the fixture CLI.
	 * @param name the test's own directory name
	 * @return the working directory
	 */
	static Path workingDirectory(String name) {
		try {
			Path work = Files.createDirectories(ROOT.resolve(name));
			Files.copy(Path.of("..", "agent-client-claude", "src", "test", "resources", "retained-child.py"), cli(),
					StandardCopyOption.REPLACE_EXISTING);
			Files.setPosixFilePermissions(cli(), PosixFilePermissions.fromString("rwx------"));
			return work;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static Path cli() {
		return ROOT.resolve("claude-fixture.py");
	}

}
