/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode;

import java.time.Duration;
import java.util.Map;

import com.alibaba.qwen.code.cli.protocol.data.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QwenCodeAgentOptionsTest {

	@Test
	void defaultOptionsShouldHaveExpectedValues() {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder().build();

		assertThat(options.getModel()).isEqualTo("qwen3-coder");
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(10));
		assertThat(options.isYolo()).isTrue();
		assertThat(options.getExecutablePath()).isNull();
		assertThat(options.getWorkingDirectory()).isNull();
		assertThat(options.getEnvironmentVariables()).isEmpty();
		assertThat(options.getExtras()).isEmpty();
	}

	@Test
	void builderShouldSetAllFields() {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder()
			.model("qwen3-coder-flash")
			.timeout(Duration.ofMinutes(5))
			.yolo(false)
			.permissionMode(PermissionMode.PLAN)
			.executablePath("/usr/local/bin/qwen")
			.workingDirectory("/tmp/workspace")
			.environmentVariables(Map.of("KEY", "value"))
			.extras(Map.of("custom", "option"))
			.build();

		assertThat(options.getModel()).isEqualTo("qwen3-coder-flash");
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(options.isYolo()).isFalse();
		assertThat(options.getPermissionMode()).isEqualTo(PermissionMode.PLAN);
		assertThat(options.getExecutablePath()).isEqualTo("/usr/local/bin/qwen");
		assertThat(options.getWorkingDirectory()).isEqualTo("/tmp/workspace");
		assertThat(options.getEnvironmentVariables()).containsEntry("KEY", "value");
		assertThat(options.getExtras()).containsEntry("custom", "option");
	}

	@Test
	void yoloTrueShouldSetPermissionModeToYolo() {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder().yolo(true).build();

		assertThat(options.isYolo()).isTrue();
		assertThat(options.getPermissionMode()).isEqualTo(PermissionMode.YOLO);
	}

	@Test
	void nullEnvironmentVariablesShouldDefaultToEmpty() {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder().environmentVariables(null).build();

		assertThat(options.getEnvironmentVariables()).isEmpty();
	}

	@Test
	void nullExtrasShouldDefaultToEmpty() {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder().extras(null).build();

		assertThat(options.getExtras()).isEmpty();
	}

}
