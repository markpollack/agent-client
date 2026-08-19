/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Migrates deprecated {@code spring.ai.agents.*} properties to the new
 * {@code agent-client.*} prefix. Logs a warning for each migrated property.
 *
 * <p>
 * This allows users to keep using the old prefix during the deprecation period. The old
 * prefix will be removed in a future release.
 *
 * @author Spring AI Community
 * @since 0.16.0
 */
public class DeprecatedPropertyMigrator implements EnvironmentPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(DeprecatedPropertyMigrator.class);

	private static final Map<String, String> PREFIX_MAPPINGS = Map.of("spring.ai.agents.claude-code",
			"agent-client.claude", "spring.ai.agents.codex", "agent-client.codex", "spring.ai.agents.gemini",
			"agent-client.gemini", "spring.ai.agents.amazon-q", "agent-client.amazon-q", "spring.ai.agents.amp",
			"agent-client.amp", "spring.ai.agents.qwen-code", "agent-client.qwen-code", "spring.ai.agents.mode",
			"agent-client.mode");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> migratedProperties = new HashMap<>();
		boolean warned = false;

		for (var source : environment.getPropertySources()) {
			if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
				for (String name : enumerable.getPropertyNames()) {
					for (Map.Entry<String, String> mapping : PREFIX_MAPPINGS.entrySet()) {
						if (name.startsWith(mapping.getKey())) {
							String newName = name.replace(mapping.getKey(), mapping.getValue());
							if (environment.getProperty(newName) == null) {
								Object value = enumerable.getProperty(name);
								migratedProperties.put(newName, value);
								if (!warned) {
									logger.warn("Deprecated property prefix 'spring.ai.agents.*' detected. "
											+ "Migrate to 'agent-client.*'. Old prefix will be removed in a future release.");
									warned = true;
								}
								logger.info("Migrated property: {} -> {}", name, newName);
							}
						}
					}
				}
			}
		}

		if (!migratedProperties.isEmpty()) {
			MutablePropertySources sources = environment.getPropertySources();
			sources.addFirst(new MapPropertySource("deprecatedAgentClientProperties", migratedProperties));
		}
	}

}
