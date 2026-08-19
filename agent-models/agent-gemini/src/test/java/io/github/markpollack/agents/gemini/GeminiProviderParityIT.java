/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.gemini;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import io.github.markpollack.agents.geminisdk.GeminiClient;
import io.github.markpollack.agents.geminisdk.exceptions.GeminiSDKException;
import io.github.markpollack.agents.geminisdk.transport.CLIOptions;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.tck.Provider;
import io.github.markpollack.agents.tck.ProviderParityTCK;
import io.github.markpollack.sandbox.LocalSandbox;

/**
 * Parity TCK wired for Gemini CLI provider.
 *
 * <p>
 * Requires GEMINI_API_KEY or GOOGLE_API_KEY. Skips gracefully if unavailable.
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
@EnabledIf("hasGeminiApiKey")
class GeminiProviderParityIT extends ProviderParityTCK {

	@Override
	protected Provider getProvider() {
		return Provider.GEMINI;
	}

	static boolean hasGeminiApiKey() {
		String geminiKey = System.getenv("GEMINI_API_KEY");
		String googleKey = System.getenv("GOOGLE_API_KEY");
		return (geminiKey != null && !geminiKey.trim().isEmpty()) || (googleKey != null && !googleKey.trim().isEmpty());
	}

	@BeforeEach
	void setUp() {
		try {
			this.sandbox = new LocalSandbox(tempDir);

			CLIOptions cliOptions = CLIOptions.builder().debug(true).yoloMode(true).build();

			GeminiClient geminiClient = GeminiClient.create(cliOptions, tempDir);

			GeminiAgentOptions options = GeminiAgentOptions.builder()
				.model("gemini-3.5-flash")
				.timeout(Duration.ofMinutes(3))
				.yolo(true)
				.build();

			this.agentModel = new GeminiAgentModel(geminiClient, options, sandbox);

			assumeTrue(agentModel.isAvailable(), "Gemini CLI must be available");
		}
		catch (GeminiSDKException e) {
			assumeTrue(false, "Failed to initialize Gemini CLI: " + e.getMessage());
		}
	}

	@Override
	protected AgentOptions createShortTimeoutOptions() {
		return GeminiAgentOptions.builder()
			.model("gemini-3.5-flash")
			.timeout(Duration.ofSeconds(10))
			.yolo(true)
			.build();
	}

}
