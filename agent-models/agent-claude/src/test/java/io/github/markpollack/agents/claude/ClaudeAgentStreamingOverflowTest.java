/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import io.github.markpollack.agents.model.AgentResponse;
import reactor.core.publisher.Flux;

import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.ClaudeSyncClient;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeAgentStreamingOverflowTest {

	@TempDir
	Path directory;

	@Test
	void reportsOverflowInsteadOfSilentlyDroppingResponses() {
		withResponses(1024,
				responses -> assertThatThrownBy(() -> responses.collectList().block(Duration.ofSeconds(5)))
					.isInstanceOf(Sinks.EmissionException.class)
					.hasMessageContaining("FAIL_ZERO_SUBSCRIBER"));
	}

	@Test
	void deliversAllResponsesWhenTheBufferHasCapacity() {
		withResponses(3, responses -> assertThat(responses.collectList().block(Duration.ofSeconds(5))).hasSize(3)
			.allSatisfy(response -> assertThat(response.getText()).isEqualTo("response")));
	}

	private void withResponses(int count, Consumer<Flux<AgentResponse>> assertion) {
		var client = mock(ClaudeSyncClient.class);
		var spec = mock(ClaudeClient.SyncSpecWithOptions.class, RETURNS_SELF);
		when(spec.build()).thenReturn(client);
		ParsedMessage message = new ParsedMessage.RegularMessage(
				new AssistantMessage(List.of(new TextBlock("response"))));
		when(client.receiveResponse()).thenReturn(Collections.nCopies(count, message).iterator());

		try (var factory = mockStatic(ClaudeClient.class);
				var model = ClaudeAgentModel.builder()
					.workingDirectory(directory)
					.asyncExecutor(Runnable::run)
					.build()) {
			factory.when(() -> ClaudeClient.sync(any(CLIOptions.class))).thenReturn(spec);
			var responses = model.stream(AgentTaskRequest.builder("Produce many responses", directory).build());
			assertion.accept(responses);
			verify(client).close();
		}
	}

}
