/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteResult;
import io.github.markpollack.agents.model.*;

/**
 * Validates stream identity/terminal shape before using existing SDK outcome semantics.
 */
final class AntigravityResult {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

	private final ExecuteResult result;

	private final Map<String, Object> usage;

	private AntigravityResult(ExecuteResult result, Map<String, Object> usage) {
		this.result = result;
		this.usage = usage;
	}

	static AntigravityResult parse(byte[] bytes, byte[] stderr, int exit, Duration duration) throws IOException {
		String stream = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
		if (!stream.endsWith("\n"))
			throw new IOException("Antigravity stdout has an incomplete final record");
		JsonNode terminal = validate(stream);
		Map<String, Object> usage = terminal.path("usage").isObject() ? JSON.convertValue(terminal.get("usage"),
				new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
				}) : Map.of();
		return new AntigravityResult(
				ExecuteResult.parseStreaming(stream, new String(stderr, StandardCharsets.UTF_8), exit, duration),
				usage);
	}

	private static JsonNode validate(String stream) throws IOException {
		JsonNode terminal = null;
		String identity = null;
		for (String line : stream.split("\n", -1)) {
			if (line.isBlank())
				continue;
			if (terminal != null)
				throw new IOException("Antigravity event after terminal result");
			JsonNode event = JSON.readTree(line);
			if (event.hasNonNull("conversation_id"))
				identity = identity(identity, event.path("conversation_id").asText());
			if ("result".equals(event.path("event").asText())) {
				terminal = event.path("result");
				identity = identity(identity, terminal.path("conversation_id").asText());
			}
		}
		return requireTerminal(terminal, identity);
	}

	private static JsonNode requireTerminal(JsonNode terminal, String identity) throws IOException {
		if (terminal == null || identity == null || !terminal.path("response").isTextual()
				|| !Set.of("SUCCESS", "ERROR").contains(terminal.path("status").asText()))
			throw new IOException("Missing Antigravity identity or terminal result");
		return terminal;
	}

	private static String identity(String prior, String next) throws IOException {
		if (next.isBlank() || (prior != null && !prior.equals(next)))
			throw new IOException("Conflicting or missing Antigravity conversation identity");
		return next;
	}

	String nativeId() {
		return this.result.getConversationId();
	}

	AgentResponse response(String model, Duration duration, int exit, String connectionId) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("exitCode", exit);
		facts.put("connectionId", connectionId);
		facts.put("nativeReason", this.result.getStatus());
		facts.put("nativeError", Optional.ofNullable(this.result.getError()).orElse(""));
		facts.put("softDenied", this.result.isSoftDenied());
		facts.put("unrecoveredError", this.result.hasUnrecoveredError());
		facts.put("permissionNotices", this.result.getPermissionNotices());
		facts.put("usage", this.usage);
		facts.put("costReported", false);
		facts.put("launchPath", "direct-observed-process-inventory");
		return new AgentResponse(
				List.of(new AgentGeneration(this.result.getResponse(),
						new AgentGenerationMetadata(this.result.isSuccessful() ? "SUCCESS" : "ERROR", Map.of()))),
				new AgentResponseMetadata(model, duration, nativeId(), facts));
	}

}
