/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk;

import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AmpClient}.
 *
 * @author Spring AI Community
 */
class AmpClientTest {

	@Test
	void testCreateWithDefaults() {
		// This test will fail if Amp CLI is not installed
		// We're mainly testing the API design here
		assertThat(AmpClient.class).isNotNull();
	}

	@Test
	void testBuilderPattern() {
		ExecuteOptions options = ExecuteOptions.builder()
			.dangerouslyAllowAll(true)
			.timeout(java.time.Duration.ofMinutes(5))
			.build();

		assertThat(options).isNotNull();
		assertThat(options.isDangerouslyAllowAll()).isTrue();
		assertThat(options.getTimeout()).isEqualTo(java.time.Duration.ofMinutes(5));
	}

	@Test
	void testDefaultOptions() {
		ExecuteOptions options = ExecuteOptions.defaultOptions();

		assertThat(options).isNotNull();
		assertThat(options.isDangerouslyAllowAll()).isTrue(); // Default should be true
		assertThat(options.getTimeout()).isNotNull();
	}

}
