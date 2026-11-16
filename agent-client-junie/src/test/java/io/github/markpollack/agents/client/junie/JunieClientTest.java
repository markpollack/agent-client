/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecutionException;
import io.github.markpollack.agents.client.CaptureAcquisition;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.ExecutionOutcome;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Junie on the lifecycle route against an offline ACP fixture that writes a native
 * session directory the way Junie 3813.7 does. No model is called.
 */
class JunieClientTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path root;

	private Path project;

	private Path sessions;

	private Path captures;

	private Path fixture;

	@BeforeEach
	void setUp() throws IOException {
		this.project = Files.createDirectories(this.root.resolve("project"));
		this.sessions = Files.createDirectories(this.root.resolve("sessions"));
		this.captures = this.root.resolve("captures");
		this.fixture = this.root.resolve("fake-junie-acp.py");
		try (InputStream in = getClass().getResourceAsStream("/fake-junie-acp.py")) {
			Files.copy(in, this.fixture);
		}
	}

	@Test
	void aSynchronousRunSealsTheNativeSessionUnderJournalsProfile() throws Exception {
		var probe = new GenerationProbe();
		String result;
		try (var client = client(provider("ok", true), probe)) {
			result = client.goal("hello").run().getResult();
		}
		assertThat(result).isEqualTo("answer:hello");
		NativeGeneration generation = probe.sealed.get(10, TimeUnit.SECONDS).orElseThrow();
		assertThat(generation.acquisitionProfile()).isEqualTo("junie-3813.7-acp-session-files");
		assertThat(generation.complete()).as(generation.detail()).isTrue();
		assertThat(generation.detail()).isEmpty();
		assertThat(NativeGeneration.sha256(generation.manifest())).isEqualTo(generation.manifestSha256());

		JsonNode manifest = JSON.readTree(generation.manifest().toFile());
		String session = manifest.path("process").path("acp_session_id").asText();
		var member = generation.members().get(0);
		assertThat(manifest.path("kind").asText()).isEqualTo("agent-journal/acquired-execution");
		assertThat(manifest.path("version").asText()).isEqualTo("0.1");
		assertThat(manifest.path("profile_id").asText()).isEqualTo("junie/3813.7/acp+summary+session-events");
		assertThat(manifest.path("acquisition_uid").asText()).isEqualTo(member.executionId());
		assertThat(manifest.path("complete").asBoolean()).isTrue();
		assertThat(manifest.path("capture_errors")).isEmpty();
		JsonNode run = manifest.path("run");
		assertThat(run.path("run_uid").asText()).isEqualTo(member.executionId());
		assertThat(run.path("session_uid").asText()).isEqualTo(session);
		assertThat(run.path("cli").path("version").asText()).isEqualTo("3813.7");
		assertThat(run.path("launch_settings").path("route").asText()).isEqualTo("acp_stdio");
		assertThat(run.path("launch_settings").path("working_directory").asText()).isEqualTo(this.project.toString());
		JsonNode process = manifest.path("process");
		assertThat(process.path("cwd").asText()).isEqualTo(this.project.toString());
		assertThat(process.path("client_session_uid").asText()).isEqualTo(generation.sessionId());
		assertThat(process.path("connection_uid").asText()).isEqualTo(generation.connectionId());
		assertThat(process.path("acp_stop_reason").asText()).isEqualTo("end_turn");
		assertThat(process.path("native_sessions_directory").asText()).isEqualTo(this.sessions.toString());
		assertThat(process.path("native_sessions_new")).extracting(JsonNode::asText).containsExactly(session);
		for (String fact : List.of("fresh_session", "observed_resources_terminated", "sealed"))
			assertThat(process.path(fact).asBoolean()).as(fact).isTrue();
		assertThat(process.path("argv").get(0).asText()).isEqualTo(this.root.resolve("junie-ok").toString());
		JsonNode turn = manifest.path("turns").get(0);
		assertThat(turn.path("invocation_uid").asText()).isEqualTo(member.executionId());
		assertThat(turn.path("decision_uid").asText()).isEqualTo(member.decisionId());
		assertThat(turn.path("native_task_uid").asText()).isEqualTo("task-261116-120000-abcd");

		assertThat(manifest.path("sources")).hasSize(2);
		source(manifest, "summary", "summary.json", "junie_summary", "document", 1, session, generation);
		source(manifest, "session", "events.jsonl", "junie_session_events", "lines", 2, session, generation);
	}

	private void source(JsonNode manifest, String role, String artifact, String profile, String framing, int records,
			String session, NativeGeneration generation) throws IOException {
		JsonNode source = null;
		for (JsonNode candidate : manifest.path("sources"))
			if (role.equals(candidate.path("role").asText()))
				source = candidate;
		assertThat(source).as(role).isNotNull();
		Path origin = this.sessions.resolve(session).resolve(artifact);
		byte[] bytes = Files.readAllBytes(origin);
		Path copy = generation.manifest().resolveSibling(artifact);
		assertThat(Files.readAllBytes(copy)).isEqualTo(bytes);
		assertThat(source.path("artifact").asText()).isEqualTo(artifact);
		assertThat(source.path("path").asText()).isEqualTo(artifact);
		assertThat(source.path("profile").asText()).isEqualTo(profile);
		assertThat(source.path("route").asText()).isEqualTo("native_session_file");
		assertThat(source.path("origin").asText()).isEqualTo(origin.toString());
		assertThat(source.path("owner_session_uid").asText()).isEqualTo(session);
		assertThat(source.path("acquisition").asText()).isEqualTo(generation.members().get(0).executionId());
		assertThat(source.path("required").asBoolean()).isTrue();
		assertThat(source.path("archive_only").asBoolean()).isFalse();
		assertThat(source.path("state").asText()).isEqualTo("acquired");
		assertThat(source.path("bytes").asLong()).isEqualTo(bytes.length);
		assertThat(source.path("observed").asLong()).isEqualTo(bytes.length);
		assertThat(source.path("dropped").asLong()).isZero();
		assertThat(source.path("end").asText()).isEqualTo("EOF");
		assertThat(source.path("framing").asText()).isEqualTo(framing);
		assertThat(source.path("records").asLong()).isEqualTo(records);
		assertThat(source.path("sha256").asText()).isEqualTo(JunieAcquisition.sha256(bytes));
	}

	@Test
	void journalsRecorderAttachesTheRunOnlyWithJournalsJunieQualifier() throws Exception {
		Path store = this.root.resolve("archive");
		var recorder = new JournalExecutionRecorder(store, "junie-test");
		boolean qualifier = io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier
			.forProfile("junie-3813.7-acp-session-files")
			.isPresent();
		try (var client = AgentClient.builder(provider("zero", true))
			.defaultOptions(options())
			.defaultWorkingDirectory(this.project)
			.executionRecorder(recorder)
			.build()) {
			var execution = client.goal("hello").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("answer:hello");
			await(() -> execution.recording().nativeRecording().isPresent()
					&& execution.recording().delivery() instanceof io.github.markpollack.agents.client.TerminalDelivery.Completed);
			var observed = execution.recording().nativeRecording().orElseThrow();
			if (qualifier) {
				assertThat(observed.outcome()).as(observed.detail()).isEqualTo(NativeRecording.Outcome.ATTACHED);
				assertThat(observed.terminalProfile()).isEqualTo("client-terminal-junie-acp/1");
			}
			else {
				assertThat(observed.outcome()).isEqualTo(NativeRecording.Outcome.UNQUALIFIED);
				assertThat(observed.detail())
					.isEqualTo("No Journal qualifier for acquisition profile junie-3813.7-acp-session-files");
			}
			assertThat(execution.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.COMPLETED);
			assertThat(execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Success.class);
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			no-version    | Junie reported no CLI version
			no-summary    | Native summary.json is missing
			no-events     | Native events.jsonl is missing
			other-id      | summary.json sessionId session-other differs from ACP session
			other-project | summary.json projectDir /somewhere/else differs from working directory
			truncated     | events.jsonl does not end with a complete line
			two-tasks     | events.jsonl has 2 TaskStartedEvent records, not exactly one
			extra-session | New native session directories
			preexisting   | existed before the turn
			""")
	void incompleteNativeEvidenceIsSealedIncompleteWithItsReason(String mode, String reason) throws Exception {
		var probe = new GenerationProbe();
		if (mode.equals("preexisting"))
			Files.createDirectories(this.sessions.resolve("session-preexisting"));
		try (var client = client(provider(mode, true), probe)) {
			client.goal("hello").run();
		}
		NativeGeneration generation = probe.sealed.get(10, TimeUnit.SECONDS).orElseThrow();
		assertThat(generation.complete()).isFalse();
		assertThat(generation.detail()).contains(reason);
		JsonNode manifest = JSON.readTree(generation.manifest().toFile());
		assertThat(manifest.path("complete").asBoolean()).isFalse();
		assertThat(manifest.path("capture_errors").toString()).contains(reason);
		if (mode.equals("no-version"))
			assertThat(generation.acquisitionProfile()).isEqualTo("junie-UNKNOWN-acp-session-files");
		if (mode.equals("no-summary"))
			assertThat(manifest.path("sources").get(0).path("state").asText()).isEqualTo("missing");
	}

	@Test
	void junieIsLaunchedWithOnlyAllowListedAndNamedVariables() throws Exception {
		var probe = new GenerationProbe();
		try (var client = client(provider("env", true), probe)) {
			client.goal("hello").run();
		}
		NativeGeneration generation = probe.sealed.get(10, TimeUnit.SECONDS).orElseThrow();
		assertThat(generation.complete()).as(generation.detail()).isTrue();
		var names = new java.util.TreeSet<String>();
		for (String line : Files.readAllLines(generation.manifest().resolveSibling("events.jsonl")))
			if (line.contains("EnvironmentVariablesUpdatedEvent"))
				JSON.readTree(line).path("event").path("agentEvent").path("env")
					.forEach(v -> names.add(v.path("key").asText()));
		assertThat(names).as("the recorded launch environment").isNotEmpty().contains("PATH");
		// The fixture runs under python3, which may add its own; nothing of the JVM's
		// beyond the allow-list reaches it.
		assertThat(names).allSatisfy(name -> assertThat(JunieLaunchProfile.INHERITED.contains(name)
				|| name.startsWith("PYTHON") || name.equals("PWD") || name.equals("SHLVL") || name.equals("_")
				|| name.startsWith("LC_")).as(name).isTrue());
	}

	@Test
	void aSessionThatRecordsACredentialIsNotCopied() throws Exception {
		var probe = new GenerationProbe();
		var options = DefaultAgentOptions.builder()
			.workingDirectory(this.project.toString())
			.timeout(Duration.ofSeconds(20))
			.environmentVariables(Map.of("FIXTURE_API_KEY", "not-a-real-secret"))
			.build();
		try (var client = AgentClient.builder(provider("env", true))
			.defaultOptions(options)
			.defaultWorkingDirectory(this.project)
			.executionRecorder(probe)
			.build()) {
			client.goal("hello").run();
		}
		NativeGeneration generation = probe.sealed.get(10, TimeUnit.SECONDS).orElseThrow();
		assertThat(generation.complete()).isFalse();
		assertThat(generation.detail()).contains(
				"Native events.jsonl records credential-shaped environment variables [FIXTURE_API_KEY]; it was not copied");
		assertThat(generation.manifest().resolveSibling("events.jsonl")).doesNotExist();
		String manifest = Files.readString(generation.manifest());
		assertThat(manifest).doesNotContain("not-a-real-secret").contains("\"state\":\"withheld\"");
	}

	@Test
	void theLaunchEnvironmentIsTheAllowListPlusTheOptions() {
		var profile = new JunieLaunchProfile(this.sessions,
				Map.of("PATH", "/bin", "HOME", "/home/u", "OPENAI_API_KEY", "sk", "AWS_SECRET_ACCESS_KEY", "x"));
		assertThat(profile.inheritEnvironment()).isFalse();
		assertThat(profile.environment(DefaultAgentOptions.builder().environmentVariables(Map.of("A", "1")).build()))
			.containsOnlyKeys("PATH", "HOME", "A");
		assertThat(profile.environment(null)).containsOnlyKeys("PATH", "HOME");
		assertThat(JunieLaunchProfile.credentialShaped("OPENAI_API_KEY")).isTrue();
		assertThat(JunieLaunchProfile.credentialShaped("GITHUB_TOKEN")).isTrue();
		assertThat(JunieLaunchProfile.credentialShaped("SSH_AUTH_SOCK")).isFalse();
		assertThat(JunieLaunchProfile.credentialShaped("XAUTHORITY")).isFalse();
		assertThat(JunieLaunchProfile.credentialShaped("PATH")).isFalse();
	}

	@Test
	void aStoppedTurnSendsSessionCancelAndSealsItsStopReason() throws Exception {
		var probe = new GenerationProbe();
		try (var client = client(provider("hang", true), probe)) {
			var execution = client.goal("hello").runAsync();
			await(() -> Files.exists(this.root.resolve("prompted")));
			execution.cancel(false);
			assertThat(execution.awaitTermination(Duration.ofSeconds(20)).cleanupConfirmed()).isTrue();
			assertThat(execution.decision().orElseThrow().outcome()).isInstanceOf(ExecutionOutcome.Cancelled.class);
		}
		NativeGeneration generation = probe.sealed.get(20, TimeUnit.SECONDS).orElseThrow();
		assertThat(generation.complete()).isFalse();
		assertThat(generation.detail()).contains("ACP stop reason cancelled, not end_turn");
		JsonNode manifest = JSON.readTree(generation.manifest().toFile());
		assertThat(manifest.path("process").path("acp_stop_reason").asText()).isEqualTo("cancelled");
		assertThat(manifest.path("process").path("observed_resources_terminated").asBoolean()).isTrue();
	}

	@Test
	void aFailedPromptFailsTheExecution() throws Exception {
		try (var client = client(provider("error", true), new GenerationProbe())) {
			assertThatThrownBy(() -> client.goal("hello").run()).isInstanceOf(AgentExecutionException.class)
				.hasStackTraceContaining("Junie execution failed");
		}
	}

	@Test
	void withoutAnAcquisitionDirectoryNothingIsSealed() throws Exception {
		var probe = new GenerationProbe();
		try (var client = client(provider("ok", false), probe)) {
			var execution = client.goal("hello").runAsync();
			assertThat(execution.result().getResult()).isEqualTo("answer:hello");
			assertThat(probe.sealed.get(10, TimeUnit.SECONDS)).isEmpty();
			assertThat(execution.recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.UNAVAILABLE);
		}
	}

	@Test
	void retainedSessionsAreRefusedAndTheProviderDeclaresNoCapabilities() {
		var provider = JunieClientProvider.builder().build();
		assertThatThrownBy(() -> provider.open(options(), true)).isInstanceOf(UnsupportedOperationException.class);
		assertThat(provider.capabilities().retention().supported()).isFalse();
		assertThat(provider.capabilities().load().supported()).isFalse();
		assertThat(provider.capabilities().progress().supported()).isFalse();
	}

	@Test
	void aConnectionRunsOneTurnAndChecksItsDirectory() {
		var connection = JunieClientProvider.builder().workingDirectory(this.project).build().open(options());
		assertThat(connection.providerReference()).isEmpty();
		var turn = connection.prepare("e1");
		assertThatThrownBy(() -> connection.prepare("e2")).isInstanceOf(IllegalStateException.class);
		var elsewhere = io.github.markpollack.agents.model.AgentTaskRequest.builder("hello", this.root)
			.options(options())
			.build();
		assertThatThrownBy(() -> turn.call(elsewhere)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> turn.call(elsewhere)).isInstanceOf(IllegalStateException.class);
		connection.close();
	}

	@Test
	void aTurnStoppedBeforeDispatchNeverLaunches() {
		var connection = JunieClientProvider.builder().workingDirectory(this.project).build().open(options());
		var turn = connection.prepare("e1");
		turn.stop(false);
		var request = io.github.markpollack.agents.model.AgentTaskRequest.builder("hello", this.project)
			.options(options())
			.build();
		assertThatThrownBy(() -> turn.call(request)).isInstanceOf(java.util.concurrent.CancellationException.class);
		assertThat(turn.generation(null)).isEmpty();
	}

	@Test
	void secretValuesNeverReachTheRecordedCommand() {
		assertThat(JunieManifest.redact(List.of("junie", "--acp", "true", "--openai-api-key", "sk-1", "-a", "t",
				"--auth=tok", "--litellm-url", "http://localhost:11434", "--model", "qwen3:4b", "--token")))
			.containsExactly("junie", "--acp", "true", "--openai-api-key", "<redacted>", "-a", "<redacted>",
					"--auth=<redacted>", "--litellm-url", "http://localhost:11434", "--model", "qwen3:4b", "--token");
	}

	@Test
	void theBuildNumberIsTheOneJunieReports() {
		assertThat(JunieObservation.buildNumber("26.10.20 (3813.7)")).isEqualTo("3813.7");
		assertThat(JunieObservation.buildNumber("3813.7")).isEqualTo("3813.7");
		assertThat(JunieObservation.buildNumber(" ")).isNull();
		assertThat(JunieObservation.buildNumber(null)).isNull();
		assertThat(JunieManifest.profile(null)).isEqualTo("junie-UNKNOWN-acp-session-files");
		assertThat(JunieManifest.profileId("3813.7")).isEqualTo("junie/3813.7/acp+summary+session-events");
	}

	private AgentClient client(JunieClientProvider provider, GenerationProbe probe) {
		return AgentClient.builder(provider)
			.defaultOptions(options())
			.defaultWorkingDirectory(this.project)
			.executionRecorder(probe)
			.build();
	}

	private DefaultAgentOptions options() {
		return DefaultAgentOptions.builder()
			.workingDirectory(this.project.toString())
			.timeout(Duration.ofSeconds(20))
			.extras(Map.of("skip-update-check", true))
			.build();
	}

	private JunieClientProvider provider(String mode, boolean capture) throws IOException {
		Path wrapper = this.root.resolve("junie-" + mode);
		Files.writeString(wrapper, "#!/bin/sh\nexec python3 '" + this.fixture + "' '" + this.sessions + "' " + mode
				+ " \"$@\"\n", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
		var builder = JunieClientProvider.builder()
			.executable(wrapper.toString())
			.workingDirectory(this.project)
			.nativeSessionsDirectory(this.sessions);
		if (capture)
			builder.acquisitionDirectory(this.captures);
		return builder.build();
	}

	private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!condition.call()) {
			if (System.nanoTime() > deadline)
				throw new AssertionError("condition not met in time");
			Thread.sleep(20);
		}
	}

}
