/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.model.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class McpCatalogLocaleTest {

	@TempDir
	Path directory;

	@Test
	void loadsUppercaseStdioUnderTurkishDefaultLocale() throws Exception {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			Path catalog = directory.resolve("mcp.json");
			Files.writeString(catalog, """
					{"mcpServers":{"test":{"type":"STDIO","command":"test-command"}}}
					""");
			assertThat(McpServerCatalog.fromJson(catalog).getAll().get("test")).isInstanceOfSatisfying(
					McpServerDefinition.StdioDefinition.class,
					definition -> assertThat(definition.command()).isEqualTo("test-command"));
		}
		finally {
			Locale.setDefault(previous);
		}
	}

}
