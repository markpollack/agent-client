/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.amazonqsdk.AmazonQClient;
import io.github.markpollack.agents.amazonqsdk.exceptions.AmazonQSDKException;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.tck.PortableAgentOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmazonQAgentModelTest {

	@TempDir
	Path directory;

	@Test
	void sendsDefaultCommandAndMapsProcessResult() throws IOException {
		var model = new AmazonQAgentModel(client(0), null, null);
		var response = model.call(AgentTaskRequest.builder("Create café.txt", this.directory).build());
		assertThat(arguments()).containsExactly("chat", "--model", "amazon-q-developer", "--no-interactive",
				"--trust-all-tools", "Create café.txt");
		assertThat(response.getText()).isEqualTo("fixture response\n");
		assertThat(response.getMetadata().getModel()).isEqualTo("amazon-q-developer");
		assertThat(response.getMetadata().getSessionId()).isEmpty();
		assertThat(response.getMetadata().getDuration().toMillis()).isNotNegative();
		assertThat(response.getMetadata().getProviderFields()).containsEntry("exitCode", 0)
			.containsEntry("successful", true);
	}

	@Test
	void overridesProviderOptionsForOneRequestWithoutChangingDefaults() throws IOException {
		var defaults = AmazonQAgentOptions.builder().model("default-model").timeout(Duration.ofSeconds(2)).build();
		var model = new AmazonQAgentModel(client(0), defaults, null);
		var requested = AmazonQAgentOptions.builder()
			.model("request-model")
			.agent("reviewer")
			.timeout(Duration.ofSeconds(3))
			.trustTools(List.of("fs_read"))
			.verbose(true)
			.build();
		var response = model.call(AgentTaskRequest.builder("Review", this.directory).options(requested).build());
		assertThat(arguments()).containsExactly("chat", "--model", "request-model", "--agent", "reviewer",
				"--no-interactive", "--trust-tools=fs_read", "--verbose", "Review");
		assertThat(response.getMetadata().getModel()).isEqualTo("request-model");
		model.call(AgentTaskRequest.builder("Next", this.directory).build());
		assertThat(arguments()).containsExactly("chat", "--model", "default-model", "--no-interactive",
				"--trust-all-tools", "Next");
	}

	@Test
	void retainsDefaultModelAndAgentWhenRequestLeavesThemUnset() throws IOException {
		var defaults = AmazonQAgentOptions.builder().model("default-model").agent("default-agent").build();
		var requested = AmazonQAgentOptions.builder().model(null).timeout(null).trustAllTools(false).build();
		new AmazonQAgentModel(client(0), defaults, null)
			.call(AgentTaskRequest.builder("Inspect", this.directory).options(requested).build());
		assertThat(arguments()).containsExactly("chat", "--model", "default-model", "--agent", "default-agent",
				"--no-interactive", "Inspect");
	}

	@Test
	void embedsPortableInstructionsAndSchemaInThePromptArgument() throws IOException {
		var options = new PortableAgentOptions(null, false, "Use concise JSON",
				Map.of("type", "object", "required", List.of("answer")));
		new AmazonQAgentModel(client(0), null, null)
			.call(AgentTaskRequest.builder("Compute result", this.directory).options(options).build());
		assertThat(Files.readString(this.directory.resolve("argv"))).contains("Use concise JSON\n\nCompute result",
				"\"answer\"", "\"object\"");
	}

	@Test
	void retainsNonzeroExitStatusInResponse() throws IOException {
		var response = new AmazonQAgentModel(client(7), null, null)
			.call(AgentTaskRequest.builder("Fail", this.directory).build());
		assertThat(response.getMetadata().getProviderFields()).containsEntry("exitCode", 7)
			.containsEntry("successful", false);
		assertThat(response.getText()).isEqualTo("fixture response\n");
	}

	@Test
	void propagatesExecutionFailureWhenExecutableDisappears() throws IOException {
		var model = new AmazonQAgentModel(client(0), null, null);
		Files.delete(this.directory.resolve("fake-q"));
		assertThatThrownBy(() -> model.call(AgentTaskRequest.builder("Execute", this.directory).build()))
			.isInstanceOf(AmazonQSDKException.class);
	}

	@Test
	void availabilityFailureBecomesFalse() {
		AmazonQClient client = mock(AmazonQClient.class);
		when(client.isAvailable()).thenReturn(true).thenThrow(new IllegalStateException("Unavailable"));
		var model = new AmazonQAgentModel(client, null, null);
		assertThat(model.isAvailable()).isTrue();
		assertThat(model.isAvailable()).isFalse();
	}

	private List<String> arguments() throws IOException {
		return Files.readAllLines(this.directory.resolve("argv"));
	}

	private AmazonQClient client(int exitCode) throws IOException {
		Path executable = this.directory.resolve("fake-q");
		Files.writeString(executable, """
				#!/bin/sh
				printf '%s\\n' "$@" > argv
				echo 'fixture response'
				exit EXIT_CODE
				""".replace("EXIT_CODE", Integer.toString(exitCode)));
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		return AmazonQClient.create(this.directory, executable.toString());
	}

}
