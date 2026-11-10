/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.TerminalDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/** The "Failed executions and closing" snippet in async-lifecycle.md. Offline; no model call. */
class FailedExecutionsTest {

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void aFailureIsReportedAndAnUnrecordedClientStillClosesWithoutWaitingOut() throws Exception {
		AgentClient client = AgentClient.builder(new OfflineProvider()).build();
		long start = System.nanoTime();
		var outcome = FailedExecutions.runThenClose(client, "task [fail]", Duration.ofSeconds(20));
		assertThat(outcome.failure()).hasValueSatisfying(
				failure -> assertThat(failure.message()).contains("offline provider failed the turn"));
		assertThat(outcome.delivery()).isInstanceOf(TerminalDelivery.NotConfigured.class);
		assertThat(outcome.fullRecord()).isEmpty();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no recording to wait for")
			.isLessThan(Duration.ofSeconds(5));
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void aSuccessCarriesNoFailure() throws Exception {
		AgentClient client = AgentClient.builder(new OfflineProvider()).build();
		var outcome = FailedExecutions.runThenClose(client, "task", Duration.ofSeconds(20));
		assertThat(outcome.failure()).isEmpty();
		assertThat(outcome.delivery()).isInstanceOf(TerminalDelivery.NotConfigured.class);
	}

	@Test
	void onlyPendingProcessingAndResubmittedAreInFlight() {
		assertThat(FailedExecutions.inFlight(new TerminalDelivery.NotConfigured())).isFalse();
		assertThat(FailedExecutions.inFlight(new TerminalDelivery.Pending(java.time.Instant.EPOCH))).isTrue();
		assertThat(FailedExecutions.inFlight(new TerminalDelivery.Processing("a", 1, java.time.Instant.EPOCH))).isTrue();
		assertThat(FailedExecutions.inFlight(new TerminalDelivery.Resubmitted("a", 2, java.time.Instant.EPOCH))).isTrue();
		assertThat(FailedExecutions.inFlight(new TerminalDelivery.Completed("b", java.time.Instant.EPOCH, false))).isFalse();
	}

}
