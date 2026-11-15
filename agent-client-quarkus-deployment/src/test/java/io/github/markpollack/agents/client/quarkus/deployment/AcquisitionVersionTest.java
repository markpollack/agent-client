/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import io.github.markpollack.agents.client.claude.ClaudeClientProvider;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An acquisition directory without the CLI version is refused when the provider is
 * created.
 */
class AcquisitionVersionTest {

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest().withApplicationRoot(jar -> jar.addClass(Fixture.class))
		.overrideConfigKey("agent-client.lifecycle.claude.enabled", "true")
		.overrideConfigKey("agent-client.lifecycle.claude.acquisition-directory",
				Fixture.workingDirectory("acquisition").toString());

	@Inject
	Instance<ClaudeClientProvider> claude;

	@Test
	void theCliVersionIsRequired() {
		assertThatThrownBy(() -> this.claude.get()).hasStackTraceContaining(
				"agent-client.lifecycle.claude.acquisition-directory requires agent-client.lifecycle.claude.cli-version");
	}

}
