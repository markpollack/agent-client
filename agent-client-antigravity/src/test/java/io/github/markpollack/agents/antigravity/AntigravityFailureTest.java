/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.antigravitysdk.transport.AntigravityProcess;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class AntigravityFailureTest {

	@TempDir
	Path root;

	@ParameterizedTest
	@ValueSource(strings = { "io", "timeout", "interrupted" })
	void publicFailureRetainsPrimaryAndCleanupWhileTerminationRemainsUnknown(String kind) throws Exception {
		Exception primary = switch (kind) {
			case "timeout" -> new TimeoutException("transport deadline");
			case "interrupted" -> new InterruptedException("transport interrupted");
			default -> new IOException("transport failed");
		};
		var cleanup = new IllegalStateException("cleanup unconfirmed");
		try (var mocks = mockConstruction(AntigravityProcess.class, (process, context) -> {
			when(process.run(any())).thenThrow(primary);
			doThrow(cleanup).when(process).close();
		})) {
			var connection = AntigravityClientProvider.builder()
				.workingDirectory(root)
				.build()
				.open(DefaultAgentOptions.builder().build());
			AgentExecutionProvider provider = options -> connection;
			var client = AgentClient.builder(provider)
				.defaultWorkingDirectory(root)
				.cleanupTimeout(Duration.ofMillis(100))
				.build();
			try {
				var execution = client.goal("fault injection").runAsync();
				var failure = catchThrowableOfType(execution::result, AgentExecutionException.class);
				assertThat(failure.failure().code()).isEqualTo(FailureCode.PROVIDER_TRANSPORT);
				assertThat(failure.getCause()).hasCause(primary);
				assertThat(primary).hasSuppressedException(cleanup);
				var decision = execution.decision().orElseThrow();
				var termination = execution.awaitTermination(Duration.ofSeconds(3));
				assertThat(termination.cleanupConfirmed()).isFalse();
				assertThat(termination.sessionState()).isEqualTo(SessionState.UNKNOWN);
				assertThat(termination.unconfirmedResources()).isNotEmpty();
				assertThat(execution.decision()).contains(decision);
				verify(mocks.constructed().get(0), times(1)).run(any());
			}
			finally {
				assertThatThrownBy(client::close).isInstanceOf(CleanupUnconfirmedException.class);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "deadline", "cancel-false", "cancel-true" })
	void acceptedStopIsNotReplacedByLaterTransportAndCleanupFailure(String mode) throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var mocks = mockConstruction(AntigravityProcess.class, (process, context) -> {
			when(process.run(any())).thenAnswer(call -> {
				entered.countDown();
				release.await();
				throw new IOException("late transport failure");
			});
			doThrow(new IllegalStateException("cleanup unconfirmed")).when(process).close();
		})) {
			var connection = AntigravityClientProvider.builder()
				.workingDirectory(root)
				.build()
				.open(DefaultAgentOptions.builder().build());
			AgentExecutionProvider provider = options -> connection;
			var client = AgentClient.builder(provider)
				.defaultWorkingDirectory(root)
				.cleanupTimeout(Duration.ofMillis(100))
				.build();
			try {
				var request = client.goal("fault injection");
				if (mode.equals("deadline"))
					request.executionTimeout(Duration.ofMillis(500));
				var execution = request.runAsync();
				assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
				if (mode.equals("deadline")) {
					var failure = catchThrowableOfType(execution::result, AgentExecutionException.class);
					assertThat(failure.failure().code()).isEqualTo(FailureCode.TIMEOUT);
				}
				else {
					assertThat(execution.cancel(mode.equals("cancel-true"))).isTrue();
					assertThatThrownBy(execution::result).isInstanceOf(CancellationException.class);
				}
				var decision = execution.decision().orElseThrow();
				release.countDown();
				var termination = execution.awaitTermination(Duration.ofSeconds(3));
				assertThat(termination.cleanupConfirmed()).isFalse();
				assertThat(termination.sessionState()).isEqualTo(SessionState.UNKNOWN);
				assertThat(execution.decision()).contains(decision);
				verify(mocks.constructed().get(0), times(1)).run(any());
			}
			finally {
				release.countDown();
				assertThatThrownBy(client::close).isInstanceOf(CleanupUnconfirmedException.class);
			}
		}
	}

}
