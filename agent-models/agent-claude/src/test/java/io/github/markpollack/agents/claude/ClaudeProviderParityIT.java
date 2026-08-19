/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.Provider;
import io.github.markpollack.agents.tck.ProviderParityTCK;
import io.github.markpollack.claude.agent.sdk.config.ClaudeCliDiscovery;
import io.github.markpollack.sandbox.LocalSandbox;

/**
 * Parity TCK wired for Claude Code provider.
 *
 * <p>
 * Requires Claude CLI installed and authenticated. Skips gracefully if unavailable.
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
class ClaudeProviderParityIT extends ProviderParityTCK {

	@Override
	protected Provider getProvider() {
		return Provider.CLAUDE;
	}

	@BeforeEach
	void setUp() {
		assumeTrue(isClaudeCliAvailable(), "Claude CLI must be available");

		try {
			this.sandbox = new LocalSandbox(tempDir);

			ClaudeAgentOptions options = ClaudeAgentOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.timeout(Duration.ofMinutes(2))
				.yolo(true)
				.build();

			this.agentModel = ClaudeAgentModel.builder().workingDirectory(tempDir).defaultOptions(options).build();

			assumeTrue(agentModel.isAvailable(), "Claude agent must be available");
		}
		catch (Exception e) {
			assumeTrue(false, "Failed to initialize Claude CLI: " + e.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return ClaudeAgentOptions.builder()
			.model("claude-haiku-4-5-20251001")
			.timeout(Duration.ofSeconds(10))
			.yolo(true)
			.build();
	}

	private static boolean isClaudeCliAvailable() {
		try {
			return ClaudeCliDiscovery.isClaudeCliAvailable();
		}
		catch (Exception e) {
			return false;
		}
	}

}
