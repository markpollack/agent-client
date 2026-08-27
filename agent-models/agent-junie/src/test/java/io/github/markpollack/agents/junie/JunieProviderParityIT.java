/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.time.Duration;

import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.Provider;
import io.github.markpollack.agents.tck.ProviderParityTCK;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-provider parity for Junie.
 *
 * <p>
 * Unlike Grok and Antigravity, this suite is not disabled under CI. Junie authenticates
 * from {@code JUNIE_API_KEY} in the environment rather than from an interactively cached
 * credential, so it can be handed a secret and run on a hosted runner — which is what
 * makes it the first provider added here since Gemini that the parity matrix can actually
 * verify. It still skips, rather than fails, when no credential is present.
 */
class JunieProviderParityIT extends ProviderParityTCK {

	@Override
	protected String getProvider() {
		return Provider.JUNIE;
	}

	@BeforeEach
	void setUp() {
		try {
			this.sandbox = new LocalSandbox(tempDir);

			JunieAgentOptions.Builder options = JunieAgentOptions.builder().timeout(Duration.ofMinutes(5));
			JunieTestCredentials.apply(options);

			this.agentModel = JunieAgentModel.builder().defaultOptions(options.build()).build();

			assumeTrue(this.agentModel.isAvailable(), "Junie CLI must be installed and on PATH");
			assumeTrue(JunieTestCredentials.available(), "Junie needs JUNIE_API_KEY, or a BYOK key, to reach a model");
		}
		catch (Exception ex) {
			assumeTrue(false, "Failed to initialize Junie CLI: " + ex.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		JunieAgentOptions.Builder options = JunieAgentOptions.builder().timeout(Duration.ofSeconds(10));
		JunieTestCredentials.apply(options);
		return options.build();
	}

}
