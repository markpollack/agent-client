/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves Junie executes through the Agent Client Protocol: {@code junie --acp true}
 * launched by the ACP Java SDK, driven initialize → session/new → session/prompt, with a
 * real answer coming back.
 *
 * <p>
 * Requires the Junie CLI installed and authenticated. Junie needs both a JetBrains login
 * and a reachable model; a login without a JetBrains AI subscription fails with
 * {@code NO_LICENSE}, so a BYOK key is the usable path outside a subscribed account. The
 * test skips rather than fails when neither is configured.
 */
class JunieAgentModelAcpIT {

	private static final Logger logger = LoggerFactory.getLogger(JunieAgentModelAcpIT.class);

	@TempDir
	Path workspace;

	private JunieAgentModel junie;

	@BeforeEach
	void setUp() throws IOException {
		JunieAgentOptions.Builder options = JunieAgentOptions.builder().timeout(Duration.ofMinutes(8));
		JunieTestCredentials.apply(options);

		this.junie = JunieAgentModel.builder().defaultOptions(options.build()).build();

		assumeTrue(this.junie.isAvailable(), "Junie CLI must be installed and on PATH");
		assumeTrue(JunieTestCredentials.available(), "Junie needs JUNIE_API_KEY, or a BYOK key, to reach a model");

		Files.writeString(this.workspace.resolve("greeting.txt"), "Hello from the ACP integration test!\n");
	}

	@Test
	@DisplayName("ACP execution: junie --acp true returns an answer through the Java SDK")
	void executesOverAcp() {
		AgentTaskRequest request = AgentTaskRequest
			.builder("Read greeting.txt and reply with exactly the sentence it contains.", this.workspace)
			.build();

		AgentResponse response = this.junie.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResults()).isNotEmpty();

		Map<String, Object> fields = response.getMetadata().getProviderFields();
		logger.info("Junie ACP run: stopReason={} sessionId={} toolCalls={} agent={} {}", fields.get("stopReason"),
				fields.get("sessionId"), fields.get("toolCallCount"), fields.get("agentName"),
				fields.get("agentVersion"));
		logger.info("Answer: {}", response.getResults().get(0).getOutput());

		assertThat(fields.get("successful")).as("ACP run should reach end_turn").isEqualTo(true);
		assertThat((String) fields.get("sessionId")).as("session/new should return a session id").isNotBlank();
		assertThat((String) fields.get("agentName")).as("initialize should report the agent").contains("junie");
		assertThat(response.getResults().get(0).getOutput()).as("agent_message_chunk should produce an answer")
			.isNotBlank();
	}

	@Test
	@DisplayName("The ACP session id names the native events.jsonl directory")
	void acpSessionIdLocatesTheNativeTrajectory() {
		AgentTaskRequest request = AgentTaskRequest
			.builder("Read greeting.txt and reply with exactly the sentence it contains.", this.workspace)
			.build();

		AgentResponse response = this.junie.call(request);
		Map<String, Object> fields = response.getMetadata().getProviderFields();

		String sessionId = (String) fields.get("sessionId");
		String eventsPath = (String) fields.get("eventsPath");
		assertThat(sessionId).isNotBlank();

		// The correspondence this adapter depends on: the trace is a lookup keyed by the
		// ACP session id, not a search for the newest directory. If a Junie release ever
		// breaks this, it breaks here rather than silently degrading capture.
		assertThat(eventsPath).as("events.jsonl should be resolvable from the ACP session id").isNotNull();
		assertThat(Path.of(eventsPath)).exists();
		assertThat(Path.of(eventsPath).getParent().getFileName().toString()).isEqualTo(sessionId);
		logger.info("Native trajectory: {}", eventsPath);
	}

}
