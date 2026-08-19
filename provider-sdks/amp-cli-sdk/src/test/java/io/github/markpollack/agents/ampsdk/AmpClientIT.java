/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.ampsdk.exceptions.AmpSDKException;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;
import io.github.markpollack.agents.ampsdk.types.ExecuteResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link AmpClient} that require Amp CLI to be installed.
 *
 * <p>
 * Authentication can be provided via:
 * <ul>
 * <li>Session authentication: Run `amp login` (recommended)</li>
 * <li>API key: Set AMP_API_KEY environment variable</li>
 * </ul>
 *
 * @author Spring AI Community
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Amp CLI not available in CI environment")
class AmpClientIT {

	private static final Logger logger = LoggerFactory.getLogger(AmpClientIT.class);

	private AmpClient client;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		try {
			ExecuteOptions options = ExecuteOptions.builder()
				.dangerouslyAllowAll(true)
				.timeout(Duration.ofMinutes(3))
				.build();

			client = AmpClient.create(options, tempDir);
		}
		catch (AmpSDKException e) {
			logger.warn("Amp CLI not available, skipping: {}", e.getMessage());
			Assumptions.assumeTrue(false, "Amp CLI not available");
		}

		// Verify Amp CLI is available before running tests
		assumeTrue(client.isAvailable(), "Amp CLI must be available for integration tests");
	}

	@Test
	void testSimpleFileCreation() throws Exception {
		// Act
		ExecuteResult result = client.execute("Create a file named test.txt with content 'Hello from Amp'");

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.getExitCode()).isEqualTo(0);
		assertThat(result.isSuccessful()).isTrue();
		assertThat(result.getOutput()).isNotNull();
		assertThat(result.getDuration()).isNotNull();

		// Verify file was created
		Path testFile = tempDir.resolve("test.txt");
		assertThat(Files.exists(testFile)).isTrue();
		String content = Files.readString(testFile);
		assertThat(content).contains("Hello from Amp");
	}

	@Test
	void testFileListAndRead() throws Exception {
		// Arrange: Create a test file
		Path sampleFile = tempDir.resolve("sample.txt");
		Files.writeString(sampleFile, "Sample content");

		// Act
		ExecuteResult result = client.execute("List all files in the current directory and show their contents");

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.isSuccessful()).isTrue();
		assertThat(result.getOutput()).containsIgnoringCase("sample.txt");
	}

	@Test
	void testAvailabilityCheck() {
		boolean available = client.isAvailable();
		assertThat(available).isTrue();
	}

	@Test
	void testCliPathDiscovery() {
		String ampPath = client.getAmpCliPath();
		assertThat(ampPath).isNotNull();
		assertThat(ampPath).isNotEmpty();
	}

}
