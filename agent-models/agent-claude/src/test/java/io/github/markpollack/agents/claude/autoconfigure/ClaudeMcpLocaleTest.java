/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude.autoconfigure;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudeMcpLocaleTest {

	@Test
	void convertsUppercaseStdioUnderTurkishDefaultLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			var properties = new ClaudeAgentMcpProperties.McpServerProperties();
			properties.setType("STDIO");
			properties.setCommand("test-command");
			assertThat(properties.toMcpServerConfig()).isInstanceOfSatisfying(
					McpServerConfig.McpStdioServerConfig.class,
					config -> assertThat(config.command()).isEqualTo("test-command"));
		}
		finally {
			Locale.setDefault(previous);
		}
	}

}
