/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.agents.claude.ClaudeAcquisition;
import io.github.markpollack.agents.client.advisor.api.*;

@Timeout(15)
class AsyncMetadataRecordingTest {

	@Test
	void typedAcquisitionAndProviderEvidenceSurviveAdvisorFailure() throws Exception {
		var provider = new AsyncLifecycleTest.Provider();
		var acquired = new ClaudeAcquisition(Path.of("/tmp/sealed/manifest.json"), "abc", true, "invocation", "native");
		provider.answer = request -> new AgentResponse(List.of(new AgentGeneration("effect")),
				new AgentResponseMetadata("model", Duration.ZERO, "native", Map.of("claudeAcquisition", acquired)));
		AgentCallAdvisor advisor = new AgentCallAdvisor() {
			@Override
			public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
				chain.nextCall(request);
				throw new IllegalStateException("postprocessing");
			}

			@Override
			public String getName() {
				return "post-failure";
			}

			@Override
			public int getOrder() {
				return 0;
			}
		};
		try (var client = AgentClient.create(provider)) {
			var execution = client.goal("effect").advisors(advisor).runAsync();
			assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
			var evidence = execution.responseSnapshot().orElseThrow();
			assertThat(ClaudeAcquisition.from(evidence.agentResponse())).isEqualTo(acquired).isNotSameAs(acquired);
			evidence.agentResponse().getMetadata().clear();
			assertThat(ClaudeAcquisition.from(execution.responseSnapshot().orElseThrow().agentResponse()))
				.isEqualTo(acquired);
			assertThat(provider.calls).hasValue(1);
		}
	}

	@Test
	void finiteRetriesPrepareOnceAndNeverReplayProvider() throws Exception {
		var prepares = new AtomicInteger();
		var attempts = new AtomicInteger();
		ExecutionRecorder recorder = decision -> {
			prepares.incrementAndGet();
			return () -> {
				if (attempts.incrementAndGet() < 3)
					throw new RecordingDeliveryException(FailureCode.PERSISTENCE_FAILED, "confirmed transient", null,
							true, false);
				return "original-ack";
			};
		};
		var provider = new AsyncLifecycleTest.Provider();
		try (var client = AgentClient.builder(provider).executionRecorder(recorder).build()) {
			var execution = client.goal("once").runAsync();
			execution.result();
			awaitDelivery(execution);
			assertThat(execution.recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
			assertThat(prepares).hasValue(1);
			assertThat(attempts).hasValue(3);
			assertThat(provider.calls).hasValue(1);
			assertThat(execution.recording().attempts())
				.anyMatch(a -> a.observation() instanceof TerminalDelivery.Resubmitted);
		}
	}

	@Test
	void permanentRecordingFailuresDoNotRetryOrChangeResult() throws Exception {
		var attempts = new AtomicInteger();
		ExecutionRecorder recorder = decision -> () -> {
			attempts.incrementAndGet();
			throw new RecordingDeliveryException(FailureCode.RECORDING_CONFLICT, "conflict", null, false, false);
		};
		try (var client = AgentClient.builder(new AsyncLifecycleTest.Provider()).executionRecorder(recorder).build()) {
			var execution = client.goal("done").runAsync();
			execution.result();
			awaitDelivery(execution);
			var failure = (TerminalDelivery.Failed) execution.recording().delivery();
			assertThat(failure.failure().code()).isEqualTo(FailureCode.RECORDING_CONFLICT);
			assertThat(failure.acceptanceUnknown()).isFalse();
			assertThat(attempts).hasValue(1);
			assertThat(execution.result().isSuccessful()).isTrue();
		}
		assertThatThrownBy(() -> new RecordingDeliveryException(FailureCode.TIMEOUT, "invalid", null, false, false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static void awaitDelivery(AgentExecution execution) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed)
				&& System.nanoTime() < deadline) {
			if (execution.recording().delivery() instanceof TerminalDelivery.Failed f && !f.retryable())
				break;
			Thread.yield();
		}
	}

}
