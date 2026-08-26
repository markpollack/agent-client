/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests for the parts of {@link JunieAgentModel} that do not need a live CLI.
 */
class JunieAgentModelTest {

	@TempDir
	Path workspace;

	@Test
	@DisplayName("ACP mode and the project directory are always on the command line")
	void alwaysLaunchesInAcpMode() {
		List<String> args = JunieAgentModel.buildLaunchArgs(this.workspace, JunieAgentOptions.builder().build());

		assertThat(args).containsSequence("--acp", "true");
		assertThat(args).containsSequence("--project", this.workspace.toString());
	}

	@Test
	@DisplayName("Neutral model and effort map onto Junie's own flags")
	void mapsNeutralOptionsOntoFlags() {
		JunieAgentOptions options = JunieAgentOptions.builder().model("gpt-5.3-codex").effort("high").build();

		List<String> args = JunieAgentModel.buildLaunchArgs(this.workspace, options);

		assertThat(args).containsSequence("--model", "gpt-5.3-codex");
		assertThat(args).containsSequence("--effort", "high");
	}

	@Test
	@DisplayName("Provider flags ride extras rather than being mirrored into fields")
	void extrasBecomeCommandLineFlags() {
		JunieAgentOptions options = JunieAgentOptions.builder()
			.extra("provider", "openai")
			.extra("openai-api-key", "sk-test")
			.build();

		List<String> args = JunieAgentModel.buildLaunchArgs(this.workspace, options);

		assertThat(args).containsSequence("--provider", "openai");
		assertThat(args).containsSequence("--openai-api-key", "sk-test");
	}

	@Test
	@DisplayName("A true boolean extra is a bare flag; a false one is omitted entirely")
	void booleanExtrasBecomeBareFlags() {
		JunieAgentOptions options = JunieAgentOptions.builder()
			.extra("skip-update-check", true)
			.extra("brave", false)
			.build();

		List<String> args = JunieAgentModel.buildLaunchArgs(this.workspace, options);

		assertThat(args).contains("--skip-update-check");
		assertThat(args).doesNotContain("--brave");
		// A bare flag must not consume the next token as its value.
		assertThat(args.indexOf("--skip-update-check")).isEqualTo(args.size() - 1);
	}

	@Test
	@DisplayName("Capture is on by default and opt-out")
	void captureIsOnByDefault() {
		// The default is the whole point: a provider that runs and silently produces no
		// journal is the failure this wiring exists to prevent.
		assertThat(JunieAgentModel.builder().build()).isNotNull();
		assertThat(JunieAgentModel.builder().captureEnabled(false).build()).isNotNull();
	}

	@Test
	@DisplayName("A launch failure is reported as an error response, not an exception")
	void launchFailureBecomesErrorResponse() {
		JunieAgentModel model = JunieAgentModel.builder()
			.command("junie-does-not-exist-" + System.nanoTime())
			.defaultOptions(JunieAgentOptions.builder().timeout(Duration.ofSeconds(5)).build())
			.build();

		AgentResponse response = model.call(AgentTaskRequest.builder("say hi", this.workspace).build());

		assertThat(response).isNotNull();
		assertThat(response.getResults()).isNotEmpty();
		Map<String, Object> fields = response.getMetadata().getProviderFields();
		assertThat(fields.get("successful")).isEqualTo(false);
		assertThat(fields).containsKey("error");
	}

	@Test
	@DisplayName("An unavailable CLI reports unavailable rather than throwing")
	void unavailableCliIsReportedNotThrown() {
		JunieAgentModel model = JunieAgentModel.builder()
			.command("junie-does-not-exist-" + System.nanoTime())
			.build();

		assertThat(model.isAvailable()).isFalse();
	}

}
