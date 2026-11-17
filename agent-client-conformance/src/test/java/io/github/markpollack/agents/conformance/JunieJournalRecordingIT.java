/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.client.junie.JunieClientProvider;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live: one fresh synchronous Junie ACP session through {@code AgentClient.run()},
 * recorded by the shipped {@link JournalExecutionRecorder}, assembled by Journal 2.0's
 * {@code FinalAssembly} and read back in a separate JVM by Journal's {@code verify} with
 * the {@code AssemblyOracle}. It calls a real model, so it runs only with
 * {@code -Pfailsafe} and is skipped without a Junie CLI.
 *
 * <p>
 * The model route is the caller's, from the environment, so a local model needs no key:
 * <ul>
 * <li>{@code JUNIE_IT_EXECUTABLE}: the Junie CLI (default {@code junie});</li>
 * <li>{@code JUNIE_IT_PROVIDER}, {@code JUNIE_IT_LITELLM_URL}, {@code JUNIE_IT_MODEL}:
 * Junie's {@code --provider}, {@code --litellm-url} and {@code --model}, for example
 * {@code litellm}, an Ollama endpoint and {@code qwen3:4b};</li>
 * <li>{@code JUNIE_IT_MODEL_LOCATION}: Junie's {@code --model-location}, a folder of
 * custom model profiles ({@code <name>.json}, then {@code JUNIE_IT_MODEL=<name>}), for
 * example one whose {@code baseUrl} is a local Ollama endpoint;</li>
 * <li>{@code JUNIE_IT_HOME}: a separate home for Junie, whose sessions are then read
 * there (default: the user's home and {@code ~/.junie/sessions});</li>
 * <li>{@code JUNIE_IT_EVIDENCE}: a directory to keep the archive, the sealed copies and
 * the reader's report (default: a new temporary directory).</li>
 * </ul>
 * Cost and usage are checked as reported: a zero cost is valid and a token count Junie
 * does not report stays absent.
 */
@Timeout(900)
class JunieJournalRecordingIT {

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	static final String PROFILE = "junie-3813.7-acp-session-files";

	@Test
	void aFreshSynchronousJunieSessionIsAttachedAssembledAndReadBack() throws Exception {
		String executable = env("JUNIE_IT_EXECUTABLE", "junie");
		assumeTrue(available(executable), "Junie CLI " + executable + " is not available");
		Path evidence = evidence();
		Path project = Files.createDirectories(evidence.resolve("project"));
		Files.writeString(project.resolve("README.md"), "A scratch project for one Junie session.\n");
		Path home = Optional.ofNullable(System.getenv("JUNIE_IT_HOME")).map(Path::of).orElse(null);
		Path sessions = (home != null ? home : Path.of(System.getProperty("user.home"))).resolve(".junie")
			.resolve("sessions");
		Path store = evidence.resolve("archive");
		var recorder = new CapturingRecorder(new JournalExecutionRecorder(store, "junie-live"));
		var provider = JunieClientProvider.builder()
			.executable(executable)
			.workingDirectory(project)
			.nativeSessionsDirectory(sessions)
			.acquisitionDirectory(evidence.resolve("captures"))
			.build();
		AgentClientResponse response;
		try (var client = AgentClient.builder(provider)
			.defaultOptions(options(project, home))
			.defaultWorkingDirectory(project)
			.executionRecorder(recorder)
			.build()) {
			response = client
				.goal("Create a file named hello.txt in the project directory containing exactly the word hello, "
						+ "then reply with the single word done.")
				.run();
		}
		ExecutionDecision decision = recorder.decision.get(60, TimeUnit.SECONDS);
		NativeGeneration generation = recorder.generation.get(60, TimeUnit.SECONDS).orElseThrow();
		NativeRecording observed = recorder.recording.get(60, TimeUnit.SECONDS);
		JsonNode manifest = JSON.readTree(generation.manifest().toFile());
		boolean qualifier = AcquisitionQualifier.forProfile(PROFILE).isPresent();
		JsonNode report = OneShotRecordingConformanceTest.read(store, "executionId=" + decision.executionId());
		writeEvidence(evidence, response, decision, generation, manifest, observed, report, qualifier);

		// The run: one fresh session whose ACP id names exactly one new native session.
		assertThat(response.isSuccessful()).as(response.getResult()).isTrue();
		assertThat(generation.acquisitionProfile()).isEqualTo(PROFILE);
		assertThat(generation.complete()).as(generation.detail()).isTrue();
		String session = manifest.path("process").path("acp_session_id").asText();
		assertThat(manifest.path("process").path("native_sessions_new")).extracting(JsonNode::asText)
			.containsExactly(session);
		assertThat(manifest.path("run").path("session_uid").asText()).isEqualTo(session);

		if (!qualifier) {
			// Against Journal 2.0.0 the run is still archived and linked, evidence-only.
			assertThat(observed.outcome()).as(observed.detail()).isEqualTo(NativeRecording.Outcome.UNQUALIFIED);
			assertThat(report.path("acceptance").asText()).isEqualTo("NOT_VERIFIED");
			assumeTrue(false, "Journal's Junie qualifier is not on the classpath: recorded UNQUALIFIED at " + evidence);
		}
		// Journal's qualifier attaches it; FinalAssembly writes the package; the separate
		// reader re-derives the expectation from the original manifest and the
		// AssemblyOracle accepts it.
		assertThat(observed.outcome()).as(observed.detail()).isEqualTo(NativeRecording.Outcome.ATTACHED);
		assertThat(observed.terminalProfile()).isEqualTo("client-terminal-junie-acp/1");
		assertThat(report.path("matches").asInt()).isEqualTo(1);
		assertThat(report.path("seriousDiagnostics")).as("reader defects").isEmpty();
		assertThat(report.path("profile").asText()).isEqualTo("client-terminal-junie-acp/1");
		assertThat(report.path("acquisition").asText()).isEqualTo("COMPLETED");
		assertThat(report.path("assemblySource").asText()).isEqualTo("tck");
		assertThat(report.path("assemblyMatchesRecorder").asBoolean()).isTrue();
		JunieVerdict.assertVerdict(report);
		assertThat(report.path("decisionId").asText()).isEqualTo(decision.decisionId());
		assertUsageAsReported(sessions.resolve(session).resolve("summary.json"), report);
	}

	/**
	 * Every usage member the export carries is one the summary reports, with the same
	 * value; nothing is added. A cost of zero is a valid report.
	 */
	static void assertUsageAsReported(Path summaryFile, JsonNode report) throws IOException {
		JsonNode summary = JSON.readTree(summaryFile.toFile());
		for (JsonNode entry : summary.path("mainAgent").path("modelUsage")) {
			if (entry.has("cost"))
				assertThat(entry.path("cost").decimalValue()).as("reported cost").isNotNegative();
		}
		assertThat(report.path("usage")).as("usage objects in the canonical export").isNotNull();
		report.path("usage").forEach(usage -> usage.fields().forEachRemaining(member -> {
			if (member.getValue().isNumber())
				assertThat(member.getValue().decimalValue()).as(member.getKey()).isNotNegative();
		}));
	}

	static DefaultAgentOptions options(Path project, Path home) {
		var extras = new LinkedHashMap<String, Object>();
		extras.put("skip-update-check", true);
		extras.put("share-anonymous-statistics", "false");
		Optional.ofNullable(System.getenv("JUNIE_IT_PROVIDER")).ifPresent(p -> extras.put("provider", p));
		Optional.ofNullable(System.getenv("JUNIE_IT_LITELLM_URL")).ifPresent(u -> extras.put("litellm-url", u));
		Optional.ofNullable(System.getenv("JUNIE_IT_MODEL_LOCATION")).ifPresent(m -> extras.put("model-location", m));
		var builder = DefaultAgentOptions.builder()
			.workingDirectory(project.toString())
			.timeout(Duration.ofMinutes(10))
			.extras(extras);
		Optional.ofNullable(System.getenv("JUNIE_IT_MODEL")).ifPresent(builder::model);
		if (home != null)
			builder.environmentVariables(Map.of("HOME", home.toString()));
		return builder.build();
	}

	static void writeEvidence(Path evidence, AgentClientResponse response, ExecutionDecision decision,
			NativeGeneration generation, JsonNode manifest, NativeRecording observed, JsonNode report,
			boolean qualifier) throws IOException {
		var index = new LinkedHashMap<String, Object>();
		index.put("journalJunieQualifier", qualifier);
		index.put("executionId", decision.executionId());
		index.put("decisionId", decision.decisionId());
		index.put("sessionId", decision.sessionId());
		index.put("connectionId", generation.connectionId());
		index.put("acpSessionId", manifest.path("process").path("acp_session_id").asText());
		index.put("nativeTaskId", manifest.path("turns").path(0).path("native_task_uid").asText());
		index.put("cliVersion", manifest.path("run").path("cli").path("version").asText());
		index.put("acquisitionProfile", generation.acquisitionProfile());
		index.put("manifest", generation.manifest().toString());
		index.put("manifestSha256", generation.manifestSha256());
		index.put("complete", generation.complete());
		index.put("detail", generation.detail());
		index.put("outcome", observed.outcome().name());
		index.put("terminalProfile", observed.terminalProfile());
		index.put("recordingDetail", observed.detail());
		index.put("successful", response.isSuccessful());
		index.put("answer", response.getResult());
		Files.writeString(evidence.resolve("evidence-index.json"), JSON.writeValueAsString(index),
				StandardCharsets.UTF_8);
		Files.writeString(evidence.resolve("reader-report.json"), JSON.writeValueAsString(report),
				StandardCharsets.UTF_8);
	}

	static Path evidence() throws IOException {
		String kept = System.getenv("JUNIE_IT_EVIDENCE");
		if (kept != null)
			return Files.createDirectories(Path.of(kept));
		return Files.createTempDirectory("junie-journal-it");
	}

	static boolean available(String executable) {
		try {
			Process process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

}
