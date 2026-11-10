/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.mcp.McpServerCatalog;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * How-to: give an agent MCP servers. Define the servers once in a catalog, choose defaults
 * on the Client, and add more per request by name. The provider translates the chosen
 * definitions into its own CLI's MCP configuration, or refuses them.
 */
public final class McpServers {

	private McpServers() {
	}

	/**
	 * Load the catalog from a file (or a directory of {@code *.json} files) in the common
	 * {@code "mcpServers"} format. {@code ${NAME}} values expand from the environment.
	 */
	public static McpServerCatalog fromFile(Path json) {
		return McpServerCatalog.fromJson(json);
	}

	/** Or build the same catalog in code. */
	public static McpServerCatalog inCode() {
		return McpServerCatalog.builder()
			.add("files",
					new McpServerDefinition.StdioDefinition("npx",
							List.of("-y", "@modelcontextprotocol/server-filesystem", "/workspace")))
			.add("search",
					new McpServerDefinition.HttpDefinition("https://mcp.example.com/search",
							Map.of("Authorization", "Bearer token")))
			.build();
	}

	/** Register the catalog once; every request gets the default servers. */
	public static AgentClient client(AgentApi agent, McpServerCatalog catalog) {
		return AgentClient.builder(agent).mcpServerCatalog(catalog).defaultMcpServers("files").build();
	}

	/** A request adds servers by name; they join the defaults. */
	public static AgentClientResponse withSearch(AgentClient client) {
		return client.goal("Find the open issues about caching").mcpServers("search").run();
	}

}
