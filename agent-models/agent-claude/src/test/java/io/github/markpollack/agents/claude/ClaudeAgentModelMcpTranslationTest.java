/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for portable McpServerDefinition → Claude McpServerConfig translation in
 * ClaudeAgentModel.
 */
class ClaudeAgentModelMcpTranslationTest {

	private ClaudeAgentModel model;

	@BeforeEach
	void setUp() {
		this.model = ClaudeAgentModel.builder().build();
	}

	@AfterEach
	void tearDown() {
		this.model.close();
	}

	@Test
	void translateStdioDefinition() {
		McpServerDefinition def = new McpServerDefinition.StdioDefinition("npx",
				List.of("-y", "@modelcontextprotocol/server-brave-search"), Map.of("BRAVE_API_KEY", "key123"));

		McpServerConfig config = this.model.toClaudeMcpServerConfig(def);

		assertThat(config).isInstanceOf(McpServerConfig.McpStdioServerConfig.class);
		McpServerConfig.McpStdioServerConfig stdio = (McpServerConfig.McpStdioServerConfig) config;
		assertThat(stdio.command()).isEqualTo("npx");
		assertThat(stdio.args()).containsExactly("-y", "@modelcontextprotocol/server-brave-search");
		assertThat(stdio.env()).containsEntry("BRAVE_API_KEY", "key123");
		assertThat(stdio.type()).isEqualTo("stdio");
	}

	@Test
	void translateSseDefinition() {
		McpServerDefinition def = new McpServerDefinition.SseDefinition("http://localhost:8080/sse",
				Map.of("Authorization", "Bearer tok"));

		McpServerConfig config = this.model.toClaudeMcpServerConfig(def);

		assertThat(config).isInstanceOf(McpServerConfig.McpSseServerConfig.class);
		McpServerConfig.McpSseServerConfig sse = (McpServerConfig.McpSseServerConfig) config;
		assertThat(sse.url()).isEqualTo("http://localhost:8080/sse");
		assertThat(sse.headers()).containsEntry("Authorization", "Bearer tok");
		assertThat(sse.type()).isEqualTo("sse");
	}

	@Test
	void translateHttpDefinition() {
		McpServerDefinition def = new McpServerDefinition.HttpDefinition("http://localhost:3000/mcp",
				Map.of("X-Api-Key", "secret"));

		McpServerConfig config = this.model.toClaudeMcpServerConfig(def);

		assertThat(config).isInstanceOf(McpServerConfig.McpHttpServerConfig.class);
		McpServerConfig.McpHttpServerConfig http = (McpServerConfig.McpHttpServerConfig) config;
		assertThat(http.url()).isEqualTo("http://localhost:3000/mcp");
		assertThat(http.headers()).containsEntry("X-Api-Key", "secret");
		assertThat(http.type()).isEqualTo("http");
	}

	@Test
	void translateStdioWithEmptyArgsAndEnv() {
		McpServerDefinition def = new McpServerDefinition.StdioDefinition("node");

		McpServerConfig config = this.model.toClaudeMcpServerConfig(def);

		McpServerConfig.McpStdioServerConfig stdio = (McpServerConfig.McpStdioServerConfig) config;
		assertThat(stdio.command()).isEqualTo("node");
		assertThat(stdio.args()).isEmpty();
		assertThat(stdio.env()).isEmpty();
	}

	@Test
	void translateSseWithNoHeaders() {
		McpServerDefinition def = new McpServerDefinition.SseDefinition("http://localhost:8080/sse");

		McpServerConfig config = this.model.toClaudeMcpServerConfig(def);

		McpServerConfig.McpSseServerConfig sse = (McpServerConfig.McpSseServerConfig) config;
		assertThat(sse.headers()).isEmpty();
	}

}
