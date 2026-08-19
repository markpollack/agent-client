/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonqsdk;

import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.amazonqsdk.transport.CLITransport;
import io.github.markpollack.agents.amazonqsdk.types.ExecuteOptions;
import io.github.markpollack.agents.amazonqsdk.types.ExecuteResult;

import java.nio.file.Paths;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AmazonQClient.
 *
 * @author Spring AI Community
 */
class AmazonQClientTest {

	@Test
	void testExecuteWithOptions() {
		// Arrange
		CLITransport mockTransport = mock(CLITransport.class);
		ExecuteResult expectedResult = new ExecuteResult("Success", 0, "amazon-q-developer", Duration.ofSeconds(5),
				null);

		when(mockTransport.execute(anyString(), any(ExecuteOptions.class))).thenReturn(expectedResult);

		AmazonQClient client = new AmazonQClient(mockTransport, Paths.get("/tmp"));
		ExecuteOptions options = ExecuteOptions.builder().trustAllTools(true).build();

		// Act
		ExecuteResult result = client.execute("Test prompt", options);

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.getOutput()).isEqualTo("Success");
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void testExecuteWithDefaultOptions() {
		// Arrange
		CLITransport mockTransport = mock(CLITransport.class);
		ExecuteResult expectedResult = new ExecuteResult("Success", 0, "amazon-q-developer", Duration.ofSeconds(5),
				null);

		when(mockTransport.execute(anyString(), any(ExecuteOptions.class))).thenReturn(expectedResult);

		AmazonQClient client = new AmazonQClient(mockTransport, Paths.get("/tmp"));

		// Act
		ExecuteResult result = client.execute("Test prompt");

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.isSuccessful()).isTrue();
	}

	@Test
	void testResume() {
		// Arrange
		CLITransport mockTransport = mock(CLITransport.class);
		ExecuteResult expectedResult = new ExecuteResult("Resumed", 0, "amazon-q-developer", Duration.ofSeconds(3),
				null);

		when(mockTransport.execute(anyString(), any(ExecuteOptions.class))).thenReturn(expectedResult);

		AmazonQClient client = new AmazonQClient(mockTransport, Paths.get("/tmp"));

		// Act
		ExecuteResult result = client.resume("Continue task");

		// Assert
		assertThat(result).isNotNull();
		assertThat(result.getOutput()).isEqualTo("Resumed");
	}

}
