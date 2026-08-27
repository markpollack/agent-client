/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.agents.acp.AcpAgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the shared {@link AcpAgentModel} drives Grok, not only Junie.
 *
 * <p>
 * This is the test that makes the generalisation more than an assertion: the model under
 * test is the same class the Junie adapter delegates to, configured with nothing but a
 * {@link GrokAcpProfile}. If ACP handling had turned out to be per-agent, this would not
 * compile, let alone pass.
 *
 * <p>
 * Requires the Grok CLI installed and authenticated — it authenticates from
 * {@code ~/.grok/auth.json} and needs no explicit ACP {@code authenticate} call. The test
 * skips rather than fails when the CLI is absent.
 */
class GrokAgentModelAcpIT {

	private static final Logger logger = LoggerFactory.getLogger(GrokAgentModelAcpIT.class);

	@TempDir
	Path workspace;

	private AcpAgentModel grok;

	@BeforeEach
	void setUp() throws IOException {
		GrokAgentOptions options = GrokAgentOptions.builder()
			.timeout(Duration.ofMinutes(8))
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		this.grok = AcpAgentModel.builder(new GrokAcpProfile()).defaultOptions(options).build();

		assumeTrue(this.grok.isAvailable(), "Grok CLI must be installed and on PATH");

		Files.writeString(this.workspace.resolve("greeting.txt"), "Hello from the ACP integration test!\n");
	}

	@Test
	@DisplayName("ACP execution: grok agent stdio runs through the shared AcpAgentModel")
	void executesOverAcp() {
		AgentTaskRequest request = AgentTaskRequest
			.builder("Read greeting.txt and reply with exactly the sentence it contains.", this.workspace)
			.build();

		AgentResponse response = this.grok.call(request);

		Map<String, Object> fields = response.getMetadata().getProviderFields();
		logger.info("Grok ACP run: stopReason={} sessionId={} toolCalls={} agent={} costUsd={}",
				fields.get("stopReason"), fields.get("sessionId"), fields.get("toolCallCount"), fields.get("agentName"),
				fields.get("costUsd"));

		assertThat(fields.get("successful")).as("ACP run should reach end_turn").isEqualTo(true);
		assertThat((String) fields.get("sessionId")).as("session/new should return a session id").isNotBlank();
		assertThat(response.getResults().get(0).getOutput()).as("agent_message_chunk should produce an answer")
			.contains("Hello from the ACP integration test");
	}

	@Test
	@DisplayName("The session id and the working directory together address the trajectory")
	void trajectoryIsAddressedByWorkingDirectoryAndSessionId() {
		AgentResponse response = this.grok
			.call(AgentTaskRequest.builder("Reply with the single word ACK.", this.workspace).build());

		Map<String, Object> fields = response.getMetadata().getProviderFields();
		String sessionId = (String) fields.get("sessionId");
		String trajectory = (String) fields.get("trajectoryPath");

		assertThat(sessionId).isNotBlank();
		// Grok partitions its sessions by working directory before session id. Asserting
		// the resolved path here is what makes a change to that scheme fail loudly
		// instead of degrading capture to silence.
		assertThat(trajectory).as("updates.jsonl should be resolvable from cwd plus session id").isNotNull();
		assertThat(Path.of(trajectory)).exists();
		assertThat(Path.of(trajectory).getParent().getFileName().toString()).isEqualTo(sessionId);
		assertThat(Path.of(trajectory).getFileName().toString()).isEqualTo("updates.jsonl");
	}

	@Test
	@DisplayName("Cost comes back over ACP without reading a trajectory")
	void costIsReportedOverTheProtocol() {
		AgentResponse response = this.grok
			.call(AgentTaskRequest.builder("Reply with the single word ACK.", this.workspace).build());

		Map<String, Object> fields = response.getMetadata().getProviderFields();

		// Grok's whole usage vector rides the prompt response _meta, which acp-core
		// 0.16.1
		// does model. Junie returns none there, which is the difference that stopped this
		// from being generalised into the shared model.
		assertThat((Double) fields.get("costUsd")).isGreaterThan(0.0);
		assertThat(fields.get("costSource")).isEqualTo("reported");

		GrokPhaseCapture capture = (GrokPhaseCapture) fields.get("phaseCapture");
		assertThat(capture).as("capture is built from the protocol, not from a parser").isNotNull();
		assertThat(capture.inputTokens()).isGreaterThan(0);
		assertThat(capture.totalCostUsd()).isGreaterThan(0.0);
	}

	@Test
	@DisplayName("An update kind this SDK cannot type is counted, not thrown")
	void unknownUpdateKindsAreCarriedOnTheResponse() {
		AgentResponse response = this.grok
			.call(AgentTaskRequest.builder("Reply with the single word ACK.", this.workspace).build());

		Map<String, Object> fields = response.getMetadata().getProviderFields();

		// acp-core 0.16.1 types ten update kinds; Grok emits session_info_update, which
		// is
		// not one of them, exactly as Junie does. Through the SDK's typed consumer that
		// is
		// a thrown InvalidTypeIdException per occurrence for every ACP agent at once.
		//
		// Grok sends it when it generates a session title, which a single short turn does
		// not reliably trigger, so this asserts the invariant that matters — an
		// untypeable
		// kind never fails a run — and leaves the kind itself to the unit test, which
		// folds a recorded stream that does contain one.
		assertThat(fields.get("successful")).isEqualTo(true);
		if (fields.containsKey("unknownUpdateKinds")) {
			logger.info("Update kinds acp-core 0.16.1 cannot type: {}", fields.get("unknownUpdateKinds"));
		}
	}

}
