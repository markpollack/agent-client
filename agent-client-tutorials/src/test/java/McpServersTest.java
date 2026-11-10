
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The MCP how-to: which definitions reach the provider. Offline; no model call. */
class McpServersTest {

	@TempDir
	Path root;

	/** Records the MCP definitions each request hands the provider. */
	static final class Recording implements AgentApi {

		final List<Map<String, McpServerDefinition>> seen = new CopyOnWriteArrayList<>();

		@Override
		public AgentResponse call(AgentTaskRequest request) {
			this.seen.add(request.options().getMcpServerDefinitions());
			return new AgentResponse(List.of(new AgentGeneration("ok")));
		}

	}

	@Test
	void defaultsReachEveryRequestAndARequestAddsByName() {
		var agent = new Recording();
		var client = McpServers.client(agent, McpServers.inCode());
		client.goal("Summarize the workspace").run();
		McpServers.withSearch(client);
		assertThat(agent.seen.get(0)).containsOnlyKeys("files");
		assertThat(agent.seen.get(1)).containsOnlyKeys("files", "search");
		assertThat(agent.seen.get(1).get("search")).isInstanceOf(McpServerDefinition.HttpDefinition.class);
	}

	@Test
	void anUnknownServerNameFailsBeforeTheProviderIsCalled() {
		var agent = new Recording();
		var client = McpServers.client(agent, McpServers.inCode());
		assertThatThrownBy(() -> client.goal("x").mcpServers("no-such-server").run())
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(agent.seen).isEmpty();
	}

	@Test
	void aCatalogFileUsesTheCommonMcpServersFormat() throws Exception {
		Path file = Files.writeString(this.root.resolve("mcp.json"), """
				{"mcpServers": {
				  "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]},
				  "search": {"type": "http", "url": "https://mcp.example.com/search"}
				}}
				""");
		var catalog = McpServers.fromFile(file);
		assertThat(catalog.getAll()).containsOnlyKeys("files", "search");
		assertThat(catalog.getAll().get("files")).isInstanceOf(McpServerDefinition.StdioDefinition.class);
	}

}
