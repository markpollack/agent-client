/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import io.github.markpollack.agents.amazonqsdk.AmazonQClient;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.agents.tck.AbstractAgentModelTCK;

import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK test implementation for AmazonQAgentModel with LocalSandbox.
 *
 * @author Spring AI Community
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Amazon Q CLI authentication not available in CI environment")
class AmazonQAgentLocalSandboxIT extends AbstractAgentModelTCK {

	@BeforeEach
	void setUp() {
		try {
			// Create LocalSandbox with temp directory
			this.sandbox = new LocalSandbox(tempDir);

			// Create Amazon Q client
			AmazonQClient amazonQClient = AmazonQClient.create(tempDir);

			// Create agent options
			AmazonQAgentOptions options = AmazonQAgentOptions.builder()
				.model("amazon-q-developer")
				.timeout(Duration.ofMinutes(3))
				.trustAllTools(true)
				.build();

			// Create agent model
			this.agentModel = new AmazonQAgentModel(amazonQClient, options, sandbox);

			// Verify Amazon Q CLI is available before running tests
			assumeTrue(agentModel.isAvailable(), "Amazon Q CLI must be available for integration tests");
		}
		catch (Exception e) {
			assumeTrue(false, "Failed to initialize Amazon Q CLI: " + e.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return AmazonQAgentOptions.builder()
			.model("amazon-q-developer")
			.timeout(Duration.ofSeconds(10)) // Short timeout for timeout testing
			.trustAllTools(true)
			.build();
	}

}
