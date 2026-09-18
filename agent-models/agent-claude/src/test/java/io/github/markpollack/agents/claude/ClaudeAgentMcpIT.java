/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.model.AgentSessionEvent;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live qualification through the production session adapter. The loopback fixture
 * is test-only. Proves discovery, exact requests, original-JVM receipts, streamed result
 * delivery and model use over two calls with conversation recall.
 */
@Tag("live")
class ClaudeAgentMcpIT {

	@TempDir
	Path workspace;

	@Test
	void discoversCallsAndUsesResultsAcrossTwoTurns() throws Exception {
		var mapper = new ObjectMapper();
		String bearer = "Bearer " + UUID.randomUUID();
		var methods = new CopyOnWriteArrayList<String>();
		var nonces = new CopyOnWriteArrayList<String>();
		var receipts = new CopyOnWriteArrayList<String>();
		var written = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", exchange -> {
			try (exchange) {
				if (!bearer.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
					exchange.sendResponseHeaders(401, -1);
					return;
				}
				if (!"POST".equals(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				JsonNode request = mapper.readTree(exchange.getRequestBody());
				String method = request.path("method").asText();
				methods.add(method);
				if (!request.has("id")) {
					exchange.sendResponseHeaders(202, -1);
					return;
				}
				Object result;
				if ("initialize".equals(method)) {
					result = Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of("tools", Map.of()),
							"serverInfo", Map.of("name", "conversation-fixture", "version", "1"));
				}
				else if ("tools/list".equals(method)) {
					result = Map.of("tools",
							List.of(Map.of("name", "lookup", "description", "Return a fresh receipt for a nonce",
									"inputSchema", Map.of("type", "object", "properties",
											Map.of("nonce", Map.of("type", "string")), "required", List.of("nonce")))));
				}
				else if ("tools/call".equals(method) && "lookup".equals(request.path("params").path("name").asText())) {
					String nonce = request.path("params").path("arguments").path("nonce").asText();
					nonces.add(nonce);
					String receipt = UUID.randomUUID().toString();
					receipts.add(receipt);
					result = Map.of("content",
							List.of(Map.of("type", "text", "text", "nonce=" + nonce + ";receipt=" + receipt)),
							"isError", false);
				}
				else {
					byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "error",
							Map.of("code", -32601, "message", "Unknown method")));
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, body.length);
					exchange.getResponseBody().write(body);
					return;
				}
				byte[] body = mapper
					.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				if ("tools/call".equals(method)) {
					written.incrementAndGet();
				}
			}
		});
		server.start();
		var registry = ClaudeAgentSessionRegistry.builder()
			.timeout(Duration.ofMinutes(3))
			.defaultOptions(ClaudeAgentOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.allowedTools(List.of("mcp__scoped__lookup"))
				.yolo(false)
				.build())
			.build();
		try (var session = registry.create(workspace, "scoped", new McpServerDefinition.HttpDefinition(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Map.of("Authorization", bearer)))) {
			for (int i = 0; i < 2; i++) {
				String nonce = UUID.randomUUID().toString();
				var events = new ArrayList<AgentSessionEvent>();
				var response = session.prompt("Call the scoped lookup tool exactly once with nonce " + nonce
						+ ". Reply with its exact receipt and nonce. Do not use other tools."
						+ (i == 1 ? " Also repeat the nonce and receipt from the first turn, from conversation memory."
								: ""),
						events::add);
				assertThat(methods).contains("initialize", "tools/list", "tools/call");
				assertThat(nonces).hasSize(i + 1).last().isEqualTo(nonce);
				assertThat(written.get()).isEqualTo(i + 1);
				String receipt = receipts.get(i);
				assertThat(events).anySatisfy(event -> {
					assertThat(event).isInstanceOf(AgentSessionEvent.ToolCall.class);
					var call = (AgentSessionEvent.ToolCall) event;
					assertThat(call.name()).isEqualTo("mcp__scoped__lookup");
					assertThat(call.arguments()).containsEntry("nonce", nonce);
				});
				assertThat(events).anySatisfy(event -> {
					assertThat(event).isInstanceOf(AgentSessionEvent.ToolResult.class);
					assertThat(((AgentSessionEvent.ToolResult) event).content().toString()).contains(receipt);
				});
				assertThat(response.getResult().getOutput()).contains(nonce, receipt);
				if (i == 1) {
					assertThat(response.getResult().getOutput()).contains(nonces.getFirst(), receipts.getFirst());
				}
				assertThat(events.getLast())
					.isEqualTo(new AgentSessionEvent.Terminal(AgentSessionEvent.Outcome.SUCCESS));
			}
			assertThat(receipts.get(0)).isNotEqualTo(receipts.get(1));
		}
		finally {
			server.stop(0);
		}
	}

}
