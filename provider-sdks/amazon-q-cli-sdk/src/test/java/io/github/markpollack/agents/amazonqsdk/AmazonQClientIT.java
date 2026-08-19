/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonqsdk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.agents.amazonqsdk.exceptions.AmazonQSDKException;
import io.github.markpollack.agents.amazonqsdk.types.ExecuteOptions;
import io.github.markpollack.agents.amazonqsdk.types.ExecuteResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for AmazonQClient.
 *
 * @author Spring AI Community
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
		disabledReason = "Amazon Q CLI authentication not available in CI environment")
class AmazonQClientIT {

	private static final Logger logger = LoggerFactory.getLogger(AmazonQClientIT.class);

	@TempDir
	Path tempDir;

	private AmazonQClient client;

	@BeforeEach
	void setUp() {
		try {
			client = AmazonQClient.create(tempDir);
		}
		catch (AmazonQSDKException e) {
			logger.warn("Amazon Q CLI not available, skipping: {}", e.getMessage());
			Assumptions.assumeTrue(false, "Amazon Q CLI not available");
		}

		// Skip tests if Amazon Q CLI is not available
		assumeTrue(client.isAvailable(), "Amazon Q CLI must be available for integration tests");
	}

	@Test
	void testSimpleFileCreation() throws Exception {
		// Arrange
		ExecuteOptions options = ExecuteOptions.builder()
			.trustAllTools(true)
			.noInteractive(true)
			.timeout(Duration.ofMinutes(3))
			.build();

		// Act
		ExecuteResult result = client.execute("Create a file named 'hello.txt' with content 'Hello World!'", options);

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.isSuccessful()).isTrue();

		// Verify the file was created
		Path helloFile = tempDir.resolve("hello.txt");
		assertThat(Files.exists(helloFile)).isTrue();

		String content = Files.readString(helloFile);
		assertThat(content.trim()).isEqualTo("Hello World!");
	}

	@Test
	void testFileListAndRead() throws Exception {
		// Arrange: Create a test file
		Path testFile = tempDir.resolve("sample.txt");
		Files.writeString(testFile, "Sample content");

		ExecuteOptions options = ExecuteOptions.builder()
			.trustAllTools(true)
			.noInteractive(true)
			.timeout(Duration.ofMinutes(3))
			.build();

		// Act
		ExecuteResult result = client.execute("List all files in the current directory and read sample.txt", options);

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.isSuccessful()).isTrue();
		assertThat(result.getOutput()).containsIgnoringCase("sample.txt");
	}

	@Test
	void testExecuteWithVerboseLogging() {
		// Arrange
		ExecuteOptions options = ExecuteOptions.builder()
			.trustAllTools(true)
			.noInteractive(true)
			.verbose(true)
			.timeout(Duration.ofMinutes(2))
			.build();

		// Act
		ExecuteResult result = client.execute("What files are in the current directory?", options);

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.isSuccessful()).isTrue();
	}

}
