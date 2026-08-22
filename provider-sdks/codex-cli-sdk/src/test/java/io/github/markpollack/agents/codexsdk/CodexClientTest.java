/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk;

import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.codexsdk.types.ApprovalPolicy;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodexClient}.
 *
 * @author Spring AI Community
 */
class CodexClientTest {

	@Test
	void testCreateWithDefaults() {
		// Testing the API design
		assertThat(CodexClient.class).isNotNull();
	}

	@Test
	void testBuilderPattern() {
		ExecuteOptions options = ExecuteOptions.builder()
			.model("gpt-5.4-mini")
			.timeout(Duration.ofMinutes(5))
			.fullAuto(true)
			.skipGitCheck(false)
			.build();

		assertThat(options).isNotNull();
		assertThat(options.getModel()).isEqualTo("gpt-5.4-mini");
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(5));
		assertThat(options.isFullAuto()).isTrue();
		assertThat(options.isSkipGitCheck()).isFalse();
	}

	@Test
	void testDefaultOptions() {
		ExecuteOptions options = ExecuteOptions.defaultOptions();

		assertThat(options).isNotNull();
		assertThat(options.getModel()).isEqualTo("gpt-5.4-mini");
		assertThat(options.isFullAuto()).isTrue();
		assertThat(options.getTimeout()).isNotNull();
	}

	@Test
	void testFullAutoImpliesSandboxAndApproval() {
		ExecuteOptions options = ExecuteOptions.builder().fullAuto(true).build();

		assertThat(options.isFullAuto()).isTrue();
		assertThat(options.getSandboxMode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);
		assertThat(options.getApprovalPolicy()).isEqualTo(ApprovalPolicy.NEVER);
	}

	@Test
	void fullAutoAndDangerousBypassAreMutuallyExclusive() {
		ExecuteOptions fullAuto = ExecuteOptions.builder().dangerouslyBypassSandbox(true).fullAuto(true).build();
		ExecuteOptions dangerous = ExecuteOptions.builder().fullAuto(true).dangerouslyBypassSandbox(true).build();

		assertThat(fullAuto.isFullAuto()).isTrue();
		assertThat(fullAuto.isDangerouslyBypassSandbox()).isFalse();
		assertThat(dangerous.isFullAuto()).isFalse();
		assertThat(dangerous.isDangerouslyBypassSandbox()).isTrue();
	}

	@Test
	void customSandboxLeavesFullAutoLevel() {
		ExecuteOptions options = ExecuteOptions.builder().fullAuto(true).sandboxMode(SandboxMode.READ_ONLY).build();

		assertThat(options.isFullAuto()).isFalse();
		assertThat(options.getSandboxMode()).isEqualTo(SandboxMode.READ_ONLY);
	}

	@Test
	void customApprovalLeavesFullAutoLevel() {
		ExecuteOptions options = ExecuteOptions.builder().fullAuto(true).approvalPolicy(ApprovalPolicy.ALWAYS).build();

		assertThat(options.isFullAuto()).isFalse();
		assertThat(options.getApprovalPolicy()).isEqualTo(ApprovalPolicy.ALWAYS);
	}

	@Test
	void testNonFullAutoWithExplicitSandbox() {
		ExecuteOptions options = ExecuteOptions.builder().fullAuto(false).sandboxMode(SandboxMode.READ_ONLY).build();

		assertThat(options.isFullAuto()).isFalse();
		assertThat(options.getSandboxMode()).isEqualTo(SandboxMode.READ_ONLY);
	}

}
