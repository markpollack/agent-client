/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AgentTaskRequest and its builder.
 *
 * @author Mark Pollack
 */
class AgentTaskRequestTest {

	@Test
	void builderWithDefaults() {
		Path workingDir = Path.of("/tmp/test");

		AgentTaskRequest request = AgentTaskRequest.builder("Fix the tests", workingDir).build();

		assertThat(request.goal()).isEqualTo("Fix the tests");
		assertThat(request.workingDirectory()).isEqualTo(workingDir);
		assertThat(request.options()).isNull();
	}

	@Test
	void builderWithNullOptions() {
		Path workingDir = Path.of("/tmp/test");

		AgentTaskRequest request = AgentTaskRequest.builder("Fix Java files", workingDir).options(null).build();

		assertThat(request.goal()).isEqualTo("Fix Java files");
		assertThat(request.workingDirectory()).isEqualTo(workingDir);
		assertThat(request.options()).isNull();
	}

}