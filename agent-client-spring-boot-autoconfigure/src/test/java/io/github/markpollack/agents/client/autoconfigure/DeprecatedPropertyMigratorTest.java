/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DeprecatedPropertyMigratorTest {

	@Test
	void migratesLegacyProviderPropertiesAndMode() {
		var environment = environment(Map.of("spring.ai.agents.claude-code.model", "test-model",
				"spring.ai.agents.codex.timeout", "30s", "spring.ai.agents.mode", "LOOSE"));
		new DeprecatedPropertyMigrator().postProcessEnvironment(environment, null);
		assertThat(environment.getProperty("agent-client.claude.model")).isEqualTo("test-model");
		assertThat(environment.getProperty("agent-client.codex.timeout")).isEqualTo("30s");
		assertThat(environment.getProperty("agent-client.mode")).isEqualTo("LOOSE");
	}

	@Test
	void explicitNewPropertyWinsOverLegacyProperty() {
		var environment = environment(
				Map.of("spring.ai.agents.codex.model", "legacy-model", "agent-client.codex.model", "selected-model"));
		new DeprecatedPropertyMigrator().postProcessEnvironment(environment, null);
		assertThat(environment.getProperty("agent-client.codex.model")).isEqualTo("selected-model");
		assertThat(environment.getPropertySources().contains("deprecatedAgentClientProperties")).isFalse();
	}

	@Test
	void leavesUnrelatedConfigurationAlone() {
		var environment = environment(Map.of("application.name", "test"));
		new DeprecatedPropertyMigrator().postProcessEnvironment(environment, null);
		assertThat(environment.getProperty("application.name")).isEqualTo("test");
		assertThat(environment.getPropertySources().contains("deprecatedAgentClientProperties")).isFalse();
	}

	@Test
	void retainsHighestPriorityLegacyValueAcrossSources() {
		var environment = environment(Map.of("spring.ai.agents.codex.model", "higher-priority"));
		environment.getPropertySources()
			.addLast(new MapPropertySource("defaults", Map.of("spring.ai.agents.codex.model", "lower-priority")));
		new DeprecatedPropertyMigrator().postProcessEnvironment(environment, null);
		assertThat(environment.getProperty("agent-client.codex.model")).isEqualTo("higher-priority");
	}

	private StandardEnvironment environment(Map<String, Object> properties) {
		var environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

}
