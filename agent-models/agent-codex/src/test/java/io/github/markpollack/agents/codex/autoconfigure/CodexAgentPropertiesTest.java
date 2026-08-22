/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex.autoconfigure;

import io.github.markpollack.agents.model.AgentClientMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexAgentPropertiesTest {

	@Test
	void looseModeDoesNotInferFullDiskBypass() {
		CodexAgentProperties properties = new CodexAgentProperties();
		properties.setMode(AgentClientMode.LOOSE);

		assertThat(properties.isFullAuto()).isTrue();
		assertThat(properties.isDangerouslyBypassSandbox()).isFalse();
		assertThat(properties.isSkipGitCheck()).isTrue();
	}

	@Test
	void dangerousBypassRequiresExplicitOptIn() {
		CodexAgentProperties properties = new CodexAgentProperties();
		properties.setDangerouslyBypassSandbox(true);

		assertThat(properties.isDangerouslyBypassSandbox()).isTrue();
	}

}
