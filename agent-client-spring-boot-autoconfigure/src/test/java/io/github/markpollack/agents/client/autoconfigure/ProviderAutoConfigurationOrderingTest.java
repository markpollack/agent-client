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
 * after it. The provider list is read from the reactor's {@code agent-models} imports
 * files, so a new provider module fails here until it is ordered.
 */
class ProviderAutoConfigurationOrderingTest {

	private static final Path AGENT_MODELS = Path.of("..", "agent-models");

	private static final String IMPORTS = "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	static List<Path> providerImports() throws IOException {
		try (Stream<Path> modules = Files.list(AGENT_MODELS)) {
			return modules.map(module -> module.resolve(IMPORTS)).filter(Files::isRegularFile).sorted().toList();
		}
	}

	static List<String> autoConfigurations(Path imports) throws IOException {
		return Files.readAllLines(imports)
			.stream()
			.map(String::strip)
			.filter(line -> !line.isEmpty() && !line.startsWith("#"))
			.toList();
	}

	@Test
	void everyProviderAutoConfigurationIsOrderedBeforeTheClient() throws IOException {
		List<String> providers = new ArrayList<>();
		for (Path imports : providerImports()) {
			providers.addAll(autoConfigurations(imports));
		}
		assertThat(providers).as("provider autoconfigurations found under %s", AGENT_MODELS.toAbsolutePath())
			.hasSizeGreaterThanOrEqualTo(9);

		String[] after = AgentClientAutoConfiguration.class.getAnnotation(AutoConfiguration.class).afterName();
		assertThat(after).as("AgentClientAutoConfiguration afterName").containsAll(providers);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("providerImports")
	void eachProviderGetsAnAgentClientBuilder(Path imports) throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		for (String name : autoConfigurations(imports)) {
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
