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
import io.github.markpollack.agents.tck.Provider;
import io.github.markpollack.agents.tck.ProviderParityTCK;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-provider parity for Grok.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Grok CLI is not available in CI")
class GrokProviderParityIT extends ProviderParityTCK {

	@Override
	protected Provider getProvider() {
		return Provider.GROK;
	}

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
