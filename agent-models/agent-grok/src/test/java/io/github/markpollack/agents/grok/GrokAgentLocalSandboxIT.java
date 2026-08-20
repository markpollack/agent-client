/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.time.Duration;

import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.AbstractAgentModelTCK;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK conformance against a live Grok CLI.
 *
 * <p>
 * Availability is probed through {@code isAvailable()} rather than an API-key environment
 * variable: Grok authenticates interactively and stores credentials in {@code ~/.grok},
 * so there is no key to look for, and requiring one would skip the suite on every machine
 * where it would actually run.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Grok CLI is not available in CI")
class GrokAgentLocalSandboxIT extends AbstractAgentModelTCK {

	@BeforeEach
	void setUp() {
		try {
			this.sandbox = new LocalSandbox(tempDir);

			ExecuteOptions executeOptions = ExecuteOptions.builder()
				.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
				.timeout(Duration.ofMinutes(3))
				.build();
			GrokClient grokClient = GrokClient.create(executeOptions, tempDir);

			GrokAgentOptions options = GrokAgentOptions.builder()
				.model("grok-4.6")
				.timeout(Duration.ofMinutes(3))
				.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
				.build();

			this.agentModel = new GrokAgentModel(grokClient, options);

			assumeTrue(this.agentModel.isAvailable(), "Grok CLI must be available and authenticated");
		}
		catch (Exception ex) {
			assumeTrue(false, "Failed to initialize Grok CLI: " + ex.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return GrokAgentOptions.builder()
			.model("grok-4.6")
			.timeout(Duration.ofSeconds(10))
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();
	}

}
