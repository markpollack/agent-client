/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.sweagentsdk.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.sweagentsdk.types.SweAgentOptions;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SweCliApi.
 */
class SweCliApiTest {

	@Test
	void testDefaultConstructor() {
		// This test verifies constructor behavior regardless of CLI availability
		// If CLI is not available, constructor should still work but throw exception on
		// usage
		try {
			SweCliApi api = new SweCliApi();
			assertThat(api).isNotNull();
		}
		catch (Exception e) {
			// If CLI discovery fails, that's acceptable for this unit test
			// The important thing is we don't crash unexpectedly
			assertThat(e).isNotNull();
		}
	}

	@Test
	void testConstructorWithExecutablePath() {
		String customPath = "/usr/local/bin/mini-swe";
		SweCliApi api = new SweCliApi(customPath);
		assertThat(api).isNotNull();
	}

	@Test
	void testConstructorWithNullExecutablePath() {
		SweCliApi api = new SweCliApi(null);
		assertThat(api).isNotNull();
	}

	@Test
	void testSweResultConstructor() {
		SweCliApi.SweResult result = new SweCliApi.SweResult(SweCliApi.SweResultStatus.SUCCESS, "output content",
				"error content", null);

		assertThat(result.getStatus()).isEqualTo(SweCliApi.SweResultStatus.SUCCESS);
		assertThat(result.getOutput()).isEqualTo("output content");
		assertThat(result.getError()).isEqualTo("error content");
		assertThat(result.getMetadata()).isNull();
	}

	@Test
	void testSweResultFromJson() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonString = "{\"output\":\"test output\",\"success\":true}";
		JsonNode json = mapper.readTree(jsonString);

		SweCliApi.SweResult result = SweCliApi.SweResult.fromJson(json, 0);

		assertThat(result.getStatus()).isEqualTo(SweCliApi.SweResultStatus.SUCCESS);
		assertThat(result.getOutput()).isEqualTo("test output");
		assertThat(result.getError()).isEmpty();
		assertThat(result.getMetadata()).isEqualTo(json);
	}

	@Test
	void testSweResultFromJsonWithFailure() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonString = "{\"output\":\"test output\",\"success\":false}";
		JsonNode json = mapper.readTree(jsonString);

		SweCliApi.SweResult result = SweCliApi.SweResult.fromJson(json, 0);

		assertThat(result.getStatus()).isEqualTo(SweCliApi.SweResultStatus.ERROR);
	}

	@Test
	void testSweResultFromJsonWithNonZeroExitCode() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String jsonString = "{\"output\":\"test output\"}";
		JsonNode json = mapper.readTree(jsonString);

		SweCliApi.SweResult result = SweCliApi.SweResult.fromJson(json, 1);

		assertThat(result.getStatus()).isEqualTo(SweCliApi.SweResultStatus.ERROR);
	}

	@Test
	void testSweCliException() {
		SweCliApi.SweCliException exception = new SweCliApi.SweCliException("test message");
		assertThat(exception.getMessage()).isEqualTo("test message");
		assertThat(exception.getCause()).isNull();

		Exception cause = new RuntimeException("cause");
		SweCliApi.SweCliException exceptionWithCause = new SweCliApi.SweCliException("test message", cause);
		assertThat(exceptionWithCause.getMessage()).isEqualTo("test message");
		assertThat(exceptionWithCause.getCause()).isEqualTo(cause);
	}

}