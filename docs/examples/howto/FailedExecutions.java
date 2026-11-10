/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionException;
import io.github.markpollack.agents.client.CaptureRecordReference;
import io.github.markpollack.agents.client.FailureInfo;
import io.github.markpollack.agents.client.RecordingSnapshot;
import io.github.markpollack.agents.client.TerminalDelivery;

/**
 * Failed executions and closing: read the original failure, let the recording settle, then
 * close the client. A client that closes first rejects the pending recording work.
 */
public final class FailedExecutions {

	private FailedExecutions() {
	}

	/** What one execution left behind. */
	public record Outcome(Optional<FailureInfo> failure, TerminalDelivery delivery,
			Optional<CaptureRecordReference> fullRecord) {
	}

	public static Outcome runThenClose(AgentClient client, String goal, Duration recordingBudget)
			throws InterruptedException, TimeoutException {
		AgentExecution execution = client.goal(goal).runAsync();
		Optional<FailureInfo> failure = Optional.empty();
		try {
			execution.result();
		}
		catch (AgentExecutionException failed) {
			failure = Optional.of(failed.failure()); // the original failure, unchanged by recording
		}
		execution.awaitTermination(Duration.ofSeconds(10));
		long deadline = System.nanoTime() + recordingBudget.toNanos();
		while (inFlight(execution.recording().delivery()) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		RecordingSnapshot recording = execution.recording();
		recording.evidence()
			.fullRecord()
			.ifPresent(record -> System.out.println(record.uri() + " " + record.sha256().orElse("")));
		client.close();
		return new Outcome(failure, recording.delivery(), recording.evidence().fullRecord());
	}

	/** Pending, processing and resubmitted deliveries are still moving; the rest are settled. */
	static boolean inFlight(TerminalDelivery delivery) {
		return delivery instanceof TerminalDelivery.Pending || delivery instanceof TerminalDelivery.Processing
				|| delivery instanceof TerminalDelivery.Resubmitted;
	}

}
