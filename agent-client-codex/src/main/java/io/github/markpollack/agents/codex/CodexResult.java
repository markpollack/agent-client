/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import io.github.markpollack.agents.model.*;

/** Interprets only captured stdout; stderr and original bytes remain independent. */
final class CodexResult {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

	private String nativeId;

	private String text = "";

	private String terminal;

	private Map<String, Object> usage = Map.of();

	private String identitySource = "stdout";

	static CodexResult parse(byte[] bytes) throws IOException {
		return parse(bytes, null);
	}

	/**
	 * A resumed turn may omit thread.started; its identity is then the exact resumed
	 * thread. An emitted identity must equal the resumed one.
	 */
	static CodexResult parse(byte[] bytes, String resumed) throws IOException {
		String stream = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
		if (!stream.endsWith("\n"))
			throw new IOException("Codex stdout has an incomplete final record");
		var result = new CodexResult();
		for (var line : stream.split("\n", -1)) {
			if (!line.isBlank())
				result.accept(JSON.readTree(line));
		}
		result.resolveIdentity(resumed);
		if (result.nativeId == null || result.terminal == null)
			throw new IOException("Missing Codex identity or terminal event");
		return result;
	}

	private void resolveIdentity(String resumed) throws IOException {
		if (resumed == null)
			return;
		if (this.nativeId == null) {
			this.nativeId = resumed;
			this.identitySource = "resume-argv";
		}
		else if (!resumed.equals(this.nativeId))
			throw new IOException("Resumed Codex thread identity mismatch");
	}

	private void accept(JsonNode event) throws IOException {
		String type = event.path("type").asText();
		if (this.terminal != null)
			throw new IOException("Codex event after terminal result");
		switch (type) {
			case "thread.started" -> identity(event);
			case "item.completed" -> item(event);
			case "turn.completed", "turn.failed" -> terminal(event, type);
			case "error" -> throw new IOException("Codex reported an error");
			default -> {
				/* Other progress remains preserved in the original source. */ }
		}
	}

	private void identity(JsonNode event) throws IOException {
		if (this.nativeId != null)
			throw new IOException("Multiple Codex thread identities");
		this.nativeId = event.path("thread_id").asText();
		if (this.nativeId.isBlank())
			throw new IOException("Empty Codex thread identity");
	}

	private void item(JsonNode event) {
		if ("agent_message".equals(event.path("item").path("type").asText()))
			this.text = event.path("item").path("text").asText();
	}

	private void terminal(JsonNode event, String type) {
		this.terminal = type;
		if (event.path("usage").isObject())
			this.usage = JSON.convertValue(event.get("usage"),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
	}

	String nativeId() {
		return this.nativeId;
	}

	String identitySource() {
		return this.identitySource;
	}

	Map<String, Object> usage() {
		return this.usage;
	}

	AgentResponse response(String model, Duration duration, int exit, CodexGeneration generation) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("exitCode", exit);
		facts.put("connectionId", generation.id);
		facts.put("nativeReason", this.terminal);
		facts.put("usage", this.usage);
		facts.put("identitySource", this.identitySource);
		facts.put("turnIndex", generation.turnIndex);
		if (generation.resumeId != null)
			facts.put("resumedThread", generation.resumeId);
		facts.put("costReported", false);
		facts.put("launchPath", "direct-observed-process-inventory");
		return new AgentResponse(
				List.of(new AgentGeneration(this.text,
						new AgentGenerationMetadata(
								exit == 0 && "turn.completed".equals(this.terminal) ? "SUCCESS" : "ERROR", Map.of()))),
				new AgentResponseMetadata(model, duration, this.nativeId, facts));
	}

}
