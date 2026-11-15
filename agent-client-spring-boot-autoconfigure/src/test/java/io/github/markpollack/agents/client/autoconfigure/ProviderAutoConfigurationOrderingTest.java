/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.github.markpollack.agents.client.AgentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every provider autoconfiguration must run before {@link AgentClientAutoConfiguration},
 * whose {@code @ConditionalOnBean} otherwise misses a provider whose class name sorts
 * after it. The provider list is read from this module's imports file and grouped by
 * provider package, so a new provider auto-configuration fails here until it is ordered.
 */
class ProviderAutoConfigurationOrderingTest {

	private static final Path IMPORTS = Path
		.of("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

	private static final String CLIENT_PACKAGE = AgentClientAutoConfiguration.class.getPackageName();

	/** Provider auto-configurations, grouped by provider package. */
	static Map<String, List<String>> providerGroups() throws IOException {
		Map<String, List<String>> groups = new TreeMap<>();
		for (String name : autoConfigurations()) {
			String pkg = name.substring(0, name.lastIndexOf('.'));
			if (!pkg.equals(CLIENT_PACKAGE)) {
				groups.computeIfAbsent(pkg, key -> new ArrayList<>()).add(name);
			}
		}
		return groups;
	}

	static Stream<String> providerImports() throws IOException {
		return providerGroups().keySet().stream();
	}

	static List<String> autoConfigurations() throws IOException {
		return Files.readAllLines(IMPORTS)
			.stream()
			.map(String::strip)
			.filter(line -> !line.isEmpty() && !line.startsWith("#"))
			.toList();
	}

	@Test
	void everyProviderAutoConfigurationIsOrderedBeforeTheClient() throws IOException {
		List<String> providers = providerGroups().values().stream().flatMap(List::stream).toList();
		assertThat(providerGroups()).as("provider packages in %s", IMPORTS.toAbsolutePath()).hasSize(9);
		assertThat(providers).as("provider auto-configurations").hasSize(10);

		String[] after = AgentClientAutoConfiguration.class.getAnnotation(AutoConfiguration.class).afterName();
		assertThat(after).as("AgentClientAutoConfiguration afterName").containsAll(providers);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providerImports")
	void eachProviderGetsAnAgentClientBuilder(String providerPackage) throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		for (String name : providerGroups().get(providerPackage)) {
			classes.add(Class.forName(name));
		}
		classes.add(AgentClientAutoConfiguration.class);

		// Lazy initialization keeps provider beans from looking for their CLIs; the
		// ordering defect is decided by bean definitions, not instances.
		new ApplicationContextRunner().withBean(LazyInitializationBeanFactoryPostProcessor.class)
			.withConfiguration(AutoConfigurations.of(classes.toArray(Class<?>[]::new)))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanFactory().getBeanNamesForType(AgentClient.Builder.class, true, false))
					.hasSize(1);
			});
	}

}
