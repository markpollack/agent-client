/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.NativeRecording;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.client.junie.JunieClientProvider;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.steps.AgentClientStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live, Workflow level: a durable Agent Workflow run whose {@code AgentClientStep} calls
 * Agent Client's synchronous {@code run()} on Junie, recorded by the shipped
 * {@link JournalExecutionRecorder}, assembled by Journal's {@code FinalAssembly} and read
 * back in a separate JVM with the {@code AssemblyOracle}, under the same
 * {@link JunieVerdict} as {@link JunieJournalRecordingIT}. Runs only with
 * {@code -Pfailsafe,workflow-proof}; it is skipped without a Junie CLI and takes the same
 * environment variables as {@link JunieJournalRecordingIT}.
 */
@Timeout(900)
class JunieWorkflowRecordingIT {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void aWorkflowAgentClientStepRunsJunieAndJournalReadsItBack() throws Exception {
		String executable = JunieJournalRecordingIT.env("JUNIE_IT_EXECUTABLE", "junie");
		assumeTrue(JunieJournalRecordingIT.available(executable), "Junie CLI " + executable + " is not available");
		Path evidence = JunieJournalRecordingIT.evidence();
		Path project = Files.createDirectories(evidence.resolve("project"));
		Files.writeString(project.resolve("README.md"), "A scratch project for one Workflow-driven Junie session.\n");
		Path sessions = Path.of(System.getProperty("user.home"), ".junie", "sessions");
		Path store = evidence.resolve("archive");
		var recorder = new CapturingRecorder(new JournalExecutionRecorder(store, "junie-workflow-live"));
		var provider = JunieClientProvider.builder()
			.executable(executable)
			.workingDirectory(project)
			.nativeSessionsDirectory(sessions)
			.acquisitionDirectory(evidence.resolve("captures"))
			.build();
		try (var client = AgentClient.builder(provider)
			.defaultOptions(JunieJournalRecordingIT.options(project, null))
			.defaultWorkingDirectory(project)
			.executionRecorder(recorder)
			.build()) {
			var adapter = new WorkflowAgentClient(client);
			var step = AgentClientStep.of(adapter,
					"Create a file named hello.txt in the project directory containing exactly the word {input}, "
							+ "then reply with the single word done.");
			var definition = Workflows.define("junie-agent-client-proof").then(step).terminate(SUCCEEDED).build();
			var registry = StepRegistry.builder().register("agent", step).build();
			var compatibility = new ExecutionCompatibility("agent-client-conformance", "junie-workflow-proof",
					Map.of("provider", "junie"));
			String runId;
			Object result;
			try (var runtime = DurableWorkflows.open(evidence.resolve("workflow").resolve("runs"), registry,
					compatibility)) {
				var run = runtime.start(definition, "junie-proof", "hello");
				var completed = runtime.resume(run.runId(), definition);
				assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				runId = run.runId();
				result = runtime.result(run.runId(), definition);
			}
			assertThat(result).isEqualTo(adapter.lastResponse.get().getResult());
			assertThat(Files.readString(project.resolve("hello.txt")).trim()).isEqualTo("hello");
			var context = adapter.lastContext.get();
			assertThat(context.runId()).isEqualTo(runId);

			var decision = recorder.decision.get(60, TimeUnit.SECONDS);
			var generation = recorder.generation.get(60, TimeUnit.SECONDS).orElseThrow();
			NativeRecording observed = recorder.recording.get(60, TimeUnit.SECONDS);
			client.close();
			JsonNode manifest = JSON.readTree(generation.manifest().toFile());
			boolean qualifier = AcquisitionQualifier.forProfile(JunieJournalRecordingIT.PROFILE).isPresent();
			JsonNode report = OneShotRecordingConformanceTest.read(store, "executionId=" + decision.executionId());
			JunieJournalRecordingIT.writeEvidence(evidence, adapter.lastResponse.get(), decision, generation,
					manifest, observed, report, qualifier);
			Files.writeString(evidence.resolve("workflow-context.json"),
					JSON.writeValueAsString(Map.of("runId", context.runId(), "invocationId", context.invocationId(),
							"attemptId", context.attemptId(), "attemptNumber", context.attemptNumber())));

			// The Workflow run, invocation and attempt reach Journal's decision record.
			assertThat(report.path("correlation").path("workflowRunId").asText()).isEqualTo(context.runId());
			assertThat(report.path("correlation").path("workflowInvocationId").asText())
				.isEqualTo(context.invocationId());
			assertThat(report.path("correlation").path("workflowAttemptId").asText()).isEqualTo(context.attemptId());
			assertThat(generation.acquisitionProfile()).isEqualTo(JunieJournalRecordingIT.PROFILE);
			assertThat(generation.complete()).as(generation.detail()).isTrue();
			assumeTrue(qualifier, "Journal's Junie qualifier is not on the classpath: recorded "
					+ observed.outcome() + " at " + evidence);
			assertThat(observed.outcome()).as(observed.detail()).isEqualTo(NativeRecording.Outcome.ATTACHED);
			assertThat(observed.terminalProfile()).isEqualTo("client-terminal-junie-acp/1");
			assertThat(report.path("matches").asInt()).isEqualTo(1);
			assertThat(report.path("seriousDiagnostics")).isEmpty();
			assertThat(report.path("assemblySource").asText()).isEqualTo("tck");
			assertThat(report.path("assemblyMatchesRecorder").asBoolean()).isTrue();
			JunieVerdict.assertVerdict(report);
			String session = manifest.path("process").path("acp_session_id").asText();
			JunieJournalRecordingIT.assertUsageAsReported(sessions.resolve(session).resolve("summary.json"), report);
		}
	}

}
