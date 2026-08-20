/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity;

import java.time.Duration;

import io.github.markpollack.agents.antigravitysdk.AntigravityClient;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.AbstractAgentModelTCK;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK conformance against a live Antigravity CLI.
 *
 * <p>
 * Availability is probed through {@code isAvailable()} rather than an API-key environment
 * variable: Antigravity authenticates interactively and caches credentials, so there is
 * no key to look for, and requiring one would skip the suite on every machine where it
 * would actually run.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Antigravity CLI is not available in CI")
class AntigravityAgentLocalSandboxIT extends AbstractAgentModelTCK {

	@BeforeEach
	void setUp() {
		try {
			this.sandbox = new LocalSandbox(tempDir);

			ExecuteOptions executeOptions = ExecuteOptions.builder()
				.dangerouslySkipPermissions(true)
				.timeout(Duration.ofMinutes(3))
				.build();
			AntigravityClient grokClient = AntigravityClient.create(executeOptions, tempDir);

			AntigravityAgentOptions options = AntigravityAgentOptions.builder()
				.model("gemini-3.1-pro-high")
				.timeout(Duration.ofMinutes(3))
				.dangerouslySkipPermissions(true)
				.build();

			this.agentModel = new AntigravityAgentModel(grokClient, options);

			assumeTrue(this.agentModel.isAvailable(), "Antigravity CLI must be available and authenticated");
		}
		catch (Exception ex) {
			assumeTrue(false, "Failed to initialize Antigravity CLI: " + ex.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return AntigravityAgentOptions.builder()
			.model("gemini-3.1-pro-high")
			.timeout(Duration.ofSeconds(10))
			.dangerouslySkipPermissions(true)
			.build();
	}

}
