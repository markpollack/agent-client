/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude.autoconfigure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards against stale Spring auto-configuration registration — every class named in this
 * module's {@code AutoConfiguration.imports} and {@code spring.factories} must be loadable.
 *
 * <p>
 * Regression guard for the org migration ({@code org.springaicommunity} → {@code io.github.markpollack}):
 * the auto-config CLASSES moved but the registration files kept the old package, so Spring Boot failed to
 * load them ({@code ClassNotFoundException}) at startup — invisible to every other test because none boots
 * the auto-config. A plain {@code Class.forName} on each registered name catches exactly that.
 */
class AutoConfigurationRegistrationTest {

	private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private static final String FACTORIES = "META-INF/spring.factories";

	@Test
	void everyAutoConfigurationImportsEntryResolves() throws IOException {
		List<String> entries = readImports(IMPORTS);
		assertThat(entries).as("entries in %s", IMPORTS).isNotEmpty();
		for (String fqcn : entries) {
			assertThatCode(() -> Class.forName(fqcn)).as("registered auto-configuration %s must resolve", fqcn)
				.doesNotThrowAnyException();
		}
	}

	@Test
	void everySpringFactoriesValueResolves() throws IOException {
		for (String fqcn : readFactoriesValues(FACTORIES)) {
			assertThatCode(() -> Class.forName(fqcn)).as("spring.factories entry %s must resolve", fqcn)
				.doesNotThrowAnyException();
		}
	}

	private List<String> readImports(String resource) throws IOException {
		List<String> out = new ArrayList<>();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertThat(in).as("resource %s present", resource).isNotNull();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
						out.add(trimmed);
					}
				}
			}
		}
		return out;
	}

	/** Collects the right-hand-side class names from a (possibly line-continued) spring.factories. */
	private List<String> readFactoriesValues(String resource) throws IOException {
		List<String> out = new ArrayList<>();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				return out; // spring.factories is optional
			}
			StringBuilder joined = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#")) {
						continue;
					}
					joined.append(trimmed.endsWith("\\") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
				}
			}
			for (String entry : joined.toString().split(";")) {
				int eq = entry.indexOf('=');
				String values = (eq >= 0) ? entry.substring(eq + 1) : entry;
				for (String fqcn : values.split(",")) {
					String trimmed = fqcn.trim();
					if (!trimmed.isEmpty()) {
						out.add(trimmed);
					}
				}
			}
		}
		return out;
	}

}
