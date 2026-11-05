/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.markpollack.agents.model.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A provider's close-time recording diagnostics are part of the closed snapshot: a
 * session close that has returned cannot be observed without them, and they are never
 * dropped by the terminal snapshot. The provider's own diagnostics call is the seam that
 * holds the ordering deterministically.
 */
@Timeout(20)
class RecordingDiagnosticsCloseTest {

	static final class Provider implements AgentExecutionProvider {

		final CountDownLatch inDiagnostics = new CountDownLatch(1);

		final CountDownLatch releaseDiagnostics = new CountDownLatch(1);

		@Override
		public Connection open(AgentOptions options) {
			return new Connection() {
				@Override
				public Turn prepare(String executionId) {
					return new Turn() {
						@Override
						public AgentResponse call(AgentTaskRequest request) {
							return new AgentResponse(
									List.of(new AgentGeneration("ok",
											new AgentGenerationMetadata("SUCCESS", Map.of()))),
									AgentResponseMetadata.builder().build());
						}

						@Override
						public void stop(boolean mayInterruptIfRunning) {
						}
					};
				}

				@Override
				public List<String> recordingDiagnostics() {
					inDiagnostics.countDown();
					try {
						if (!releaseDiagnostics.await(10, TimeUnit.SECONDS))
							throw new AssertionError("diagnostics latch");
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					return List.of("thread manifest written: fixture");
				}

				@Override
				public void close() {
				}
			};
		}

	}

	@Test
	void closeIsNotSettledBeforeItsDiagnosticsArePublishedInTheClosedSnapshot() throws Exception {
		var provider = new Provider();
		try (var client = AgentClient.builder(provider).build()) {
			var session = client.sessions().create(s -> {
			});
			assertThat(session.goal("goal").runAsync().result().getResult()).isEqualTo("ok");
			var closer = new Thread(session::close, "closer");
			closer.start();
			assertThat(provider.inDiagnostics.await(5, TimeUnit.SECONDS)).isTrue();
			closer.join(300);
			assertThat(closer.isAlive()).as("close cannot settle while diagnostics are being collected").isTrue();
			assertThat(session.inspect().state()).isNotEqualTo(SessionState.CLOSED);
			assertThat(session.inspect().diagnostics()).isEmpty();
			provider.releaseDiagnostics.countDown();
			closer.join(5_000);
			assertThat(closer.isAlive()).isFalse();
			var closed = session.inspect();
			assertThat(closed.state()).isEqualTo(SessionState.CLOSED);
			assertThat(closed.diagnostics()).containsExactly("thread manifest written: fixture");
		}
	}

}
