/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.acp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Translates portable MCP server definitions into the {@code mcpServers} of an ACP
 * {@code session/new}. Every ACP agent accepts stdio servers; HTTP and SSE servers are
 * sent only when the agent advertised that transport in {@code initialize}. A definition
 * the agent cannot take is refused before a session opens, never silently dropped.
 */
final class AcpMcpServers {

	private AcpMcpServers() {
	}

	/**
	 * @param definitions the configured servers by name
	 * @param capabilities the agent's advertised capabilities, or null when it sent none
	 * @return the servers for {@code session/new}
	 * @throws UnsupportedOperationException when the agent did not advertise a server's
	 * transport
	 */
	static List<AcpSchema.McpServer> translate(Map<String, McpServerDefinition> definitions,
			AcpSchema.AgentCapabilities capabilities) {
		AcpSchema.McpCapabilities mcp = capabilities != null ? capabilities.mcpCapabilities() : null;
		List<AcpSchema.McpServer> servers = new ArrayList<>();
		definitions.forEach((name, definition) -> servers.add(switch (definition) {
			case McpServerDefinition.StdioDefinition stdio ->
				new AcpSchema.McpServerStdio(name, stdio.command(), stdio.args(),
						stdio.env()
							.entrySet()
							.stream()
							.map(e -> new AcpSchema.EnvVariable(e.getKey(), e.getValue()))
							.toList());
			case McpServerDefinition.HttpDefinition http -> {
				require(mcp != null && Boolean.TRUE.equals(mcp.http()), name, "http");
				yield new AcpSchema.McpServerHttp(name, http.url(), headers(http.headers()));
			}
			case McpServerDefinition.SseDefinition sse -> {
				require(mcp != null && Boolean.TRUE.equals(mcp.sse()), name, "sse");
				yield new AcpSchema.McpServerSse(name, sse.url(), headers(sse.headers()));
			}
		}));
		return List.copyOf(servers);
	}

	private static List<AcpSchema.HttpHeader> headers(Map<String, String> headers) {
		return headers.entrySet().stream().map(e -> new AcpSchema.HttpHeader(e.getKey(), e.getValue())).toList();
	}

	private static void require(boolean advertised, String name, String transport) {
		if (!advertised)
			throw new UnsupportedOperationException("MCP server '" + name + "' uses " + transport
					+ ", which this ACP agent did not advertise; no session was opened");
	}

}
