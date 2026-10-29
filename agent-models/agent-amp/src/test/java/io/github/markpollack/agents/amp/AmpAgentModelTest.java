/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.github.markpollack.agents.ampsdk.AmpClient;
import io.github.markpollack.agents.ampsdk.exceptions.AmpSDKException;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.tck.PortableAgentOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmpAgentModelTest {

	@TempDir
	Path directory;

	@Test
	void sendsUtf8PromptAndMapsProcessResult() throws IOException {
		try (AmpClient client = client(0)) {
			var model = new AmpAgentModel(client, null, null);
			AgentResponse response = model.call(AgentTaskRequest.builder("Create café.txt", this.directory).build());
			assertThat(Files.readAllLines(this.directory.resolve("argv"))).containsExactly("--dangerously-allow-all",
					"-x");
			assertThat(Files.readString(this.directory.resolve("stdin"))).isEqualTo("Create café.txt");
			assertThat(response.getText()).isEqualTo("fixture response\n");
			assertThat(response.getMetadata().getModel()).isEqualTo("amp-default");
			assertThat(response.getMetadata().getDuration().toMillis()).isNotNegative();
			assertThat(response.getMetadata().getProviderFields()).containsEntry("exitCode", 0)
				.containsEntry("successful", true);
		}
	}

	@Test
	void overridesPermissionFlagForOneRequestWithoutChangingDefaults() throws IOException {
		try (AmpClient client = client(0)) {
			var defaults = AmpAgentOptions.builder().dangerouslyAllowAll(true).timeout(Duration.ofSeconds(2)).build();
			var model = new AmpAgentModel(client, defaults, null);
			var requestOptions = AmpAgentOptions.builder()
				.dangerouslyAllowAll(false)
				.model("request-model")
				.timeout(Duration.ofSeconds(3))
				.build();
			model.call(AgentTaskRequest.builder("First", this.directory).options(requestOptions).build());
			assertThat(Files.readAllLines(this.directory.resolve("argv"))).containsExactly("-x");
			model.call(AgentTaskRequest.builder("Second", this.directory).build());
			assertThat(Files.readAllLines(this.directory.resolve("argv"))).containsExactly("--dangerously-allow-all",
					"-x");
		}
	}

	@Test
	void embedsPortableInstructionsAndSchemaInStdin() throws IOException {
		try (AmpClient client = client(0)) {
			var options = new PortableAgentOptions(null, false, "Use concise JSON",
					Map.of("type", "object", "required", java.util.List.of("answer")));
			new AmpAgentModel(client, null, null)
				.call(AgentTaskRequest.builder("Compute result", this.directory).options(options).build());
			assertThat(Files.readString(this.directory.resolve("stdin"))).contains("Use concise JSON\n\nCompute result",
					"\"answer\"", "\"object\"");
		}
	}

	@Test
	void retainsNonzeroExitStatusInResponse() throws IOException {
		try (AmpClient client = client(7)) {
			var response = new AmpAgentModel(client, null, null)
				.call(AgentTaskRequest.builder("Fail", this.directory).build());
			assertThat(response.getMetadata().getProviderFields()).containsEntry("exitCode", 7)
				.containsEntry("successful", false);
			assertThat(response.getText()).isEqualTo("fixture response\n");
		}
	}

	@Test
	void reportsUnavailableAndPropagatesExecutionFailureWhenExecutableDisappears() throws IOException {
		try (AmpClient client = client(0)) {
			var model = new AmpAgentModel(client, null, null);
			assertThat(model.isAvailable()).isTrue();
			Files.delete(this.directory.resolve("fake-amp"));
			assertThat(model.isAvailable()).isFalse();
			assertThatThrownBy(() -> model.call(AgentTaskRequest.builder("Execute", this.directory).build()))
				.isInstanceOf(AmpSDKException.class);
		}
	}

	private AmpClient client(int exitCode) throws IOException {
		Path executable = this.directory.resolve("fake-amp");
		Files.writeString(executable, """
				#!/bin/sh
				if [ "$1" = "--version" ]; then
				  echo 'fixture amp'
				  exit 0
				fi
				printf '%s\\n' "$@" > argv
				cat > stdin
				echo 'fixture response'
				exit EXIT_CODE
				""".replace("EXIT_CODE", Integer.toString(exitCode)));
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		return AmpClient.create(ExecuteOptions.defaultOptions(), this.directory, executable.toString());
	}

}
