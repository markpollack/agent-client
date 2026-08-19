/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GeminiCliDiscovery utility class.
 *
 * @author Mark Pollack
 */
class GeminiCliDiscoveryTest {

	@BeforeEach
	void clearCache() {
		// Clear any cached results before each test
		GeminiCliDiscovery.clearCache();
	}

	@Test
	void testFindGeminiCommandBasic() {
		String command = GeminiCliDiscovery.findGeminiCommand();
		assertThat(command).isNotNull();
		// Should at least return "gemini" as fallback
		assertThat(command).containsIgnoringCase("gemini");
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
	void testGeminiCliAvailability() {
		boolean isAvailable = GeminiCliDiscovery.isGeminiCliAvailable();
		System.out.println("Gemini CLI available: " + isAvailable);

		String discoveredPath = GeminiCliDiscovery.getDiscoveredPath();
		System.out.println("Discovered path: " + discoveredPath);

		if (isAvailable) {
			assertThat(discoveredPath).isNotNull();
			String version = GeminiCliDiscovery.getGeminiCliVersion();
			System.out.println("CLI version: " + version);
			assertThat(version).isNotNull();
		}
		else {
			System.out.println("Gemini CLI not available - this may be expected in CI environments");
		}
	}

	@Test
	void testExtractNvmNodePath() {
		String nvmPath = "/home/user/.nvm/versions/node/v22.15.0/bin/gemini";
		String extractedPath = GeminiCliDiscovery.extractNvmNodePath(nvmPath);
		assertThat(extractedPath).isEqualTo("/home/user/.nvm/versions/node/v22.15.0/bin");

		String nonNvmPath = "/usr/local/bin/gemini";
		String extractedNonNvmPath = GeminiCliDiscovery.extractNvmNodePath(nonNvmPath);
		assertThat(extractedNonNvmPath).isNull();
	}

	@Test
	void testGetGeminiCommand() {
		// Test regular command
		String[] command = GeminiCliDiscovery.getGeminiCommand("gemini", "--help");
		assertThat(command).containsExactly("gemini", "--help");

		// Test nvm command
		String nvmPath = "/home/user/.nvm/versions/node/v22.15.0/bin/gemini";
		String[] nvmCommand = GeminiCliDiscovery.getGeminiCommand(nvmPath, "--version");
		assertThat(nvmCommand).containsExactly("/home/user/.nvm/versions/node/v22.15.0/bin/node",
				"/home/user/.nvm/versions/node/v22.15.0/bin/gemini", "--version");
	}

	@Test
	void testCommandAvailabilityForNonExistentCommand() {
		boolean available = GeminiCliDiscovery.isCommandAvailable("/nonexistent/command");
		assertThat(available).isFalse();
	}

	@Test
	void testCacheClearing() {
		// This test mainly verifies that clearCache doesn't throw exceptions
		GeminiCliDiscovery.clearCache();
		// Call a method to populate cache
		GeminiCliDiscovery.findGeminiCommand();
		// Clear again
		GeminiCliDiscovery.clearCache();
		// Should work fine
		assertThat(GeminiCliDiscovery.findGeminiCommand()).isNotNull();
	}

}