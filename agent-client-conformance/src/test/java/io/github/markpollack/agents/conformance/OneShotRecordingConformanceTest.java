/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.ApplicationCorrelation;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.ExecutionRecorder;
import io.github.markpollack.agents.client.TerminalDelivery;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.codex.CodexClientProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.zeroturnaround.exec.ProcessExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One-shot recording conformance. Each case runs a fixture CLI through the shipped
 * {@link JournalExecutionRecorder} with a full {@link ApplicationCorrelation}. A separate
 * JVM, whose classpath is Journal's capture TCK only, then reads the archive. It must
 * report full acceptance, the exact native terminal profile, all six correlation fields,
 * the fixture's usage and no cost.
 *
 * <p>
 * The matrix is fixed and every case runs: nothing is discovered, assumed or skipped.
 */
@Timeout(90)
class OneShotRecordingConformanceTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	static final ApplicationCorrelation CORRELATION = new ApplicationCorrelation(Optional.of("conformance-experiment"),
			Optional.of("conformance-experiment-run"), Optional.of("conformance-experiment-invocation"),
			Optional.of("wf-run-1"), Optional.of("wf-invocation-1:quality-1"), Optional.of("wf-attempt-1"));

	/** Codex: the native usage of its single turn, as the fixture reports it. */
	private static final Map<String, Integer> CODEX_USAGE = Map.of("input_tokens", 11, "cached_input_tokens", 3,
			"output_tokens", 4, "reasoning_output_tokens", 0);

	/**
	 * Claude: the same quantities in Anthropic's shape. Claude reports no reasoning
	 * member; zero reasoning is the absence of a thinking count, never an invented zero.
	 */
	private static final Map<String, Integer> CLAUDE_USAGE = Map.of("input_tokens", 11, "cache_read_input_tokens", 3,
			"output_tokens", 4);

	enum Case {

		CODEX_ONE_SHOT("client-terminal-codex-0.166.0/1", CODEX_USAGE),

		CLAUDE_ONE_SHOT("client-terminal-claude-2.1.316/1", CLAUDE_USAGE),

		/** The retained public factory must reach the same archive. */
		CLAUDE_ONE_SHOT_FACTORY("client-terminal-claude-2.1.316/1", CLAUDE_USAGE);

		final String profile;

		final Map<String, Integer> usage;

		Case(String profile, Map<String, Integer> usage) {
			this.profile = profile;
			this.usage = usage;
		}

	}

	@TempDir
	Path root;

	@Test
	void matrixIsFixed() {
		assertThat(Arrays.stream(Case.values()).map(Enum::name)).containsExactly("CODEX_ONE_SHOT", "CLAUDE_ONE_SHOT",
				"CLAUDE_ONE_SHOT_FACTORY");
	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void shippedRecorderArchiveIsAcceptedByTheIndependentReader(Case c) throws Exception {
		Path store = this.root.resolve("archive");
		ExecutionRecorder recorder = c == Case.CLAUDE_ONE_SHOT_FACTORY
				? JournalExecutionRecorder.claude(store, "conformance")
				: new JournalExecutionRecorder(store, "conformance");
		var provider = c == Case.CODEX_ONE_SHOT ? codex() : claude();
		var options = DefaultAgentOptions.builder()
			.workingDirectory(this.root.toString())
			.model("synthetic-model")
			.timeout(Duration.ofSeconds(20))
			.environmentVariables(Map.of("FIXTURE_SESSIONS", this.root.resolve("sessions").toString()));
		ExecutionDecision decision;
		try (var client = AgentClient.builder(provider)
			.defaultOptions(options.build())
			.defaultWorkingDirectory(this.root)
			.executionRecorder(recorder)
			.build()) {
			var execution = client.goal("success").correlation(CORRELATION).runAsync();
			assertThat(execution.result().getResult()).isEqualTo("answer:success");
			awaitDelivery(execution);
			assertThat(execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
			decision = execution.decision().orElseThrow();
		}
		JsonNode report = read(store, "executionId=" + decision.executionId());

		assertThat(report.path("matches").asInt()).as("archives for the execution").isEqualTo(1);
		assertThat(report.path("seriousDiagnostics")).as("reader defects").isEmpty();
		assertThat(report.path("profile").asText()).as("terminal profile").isEqualTo(c.profile);
		assertThat(report.path("acquisition").asText()).isEqualTo("COMPLETED");
		assertThat(report.path("acceptance").asText()).as(report.path("acceptanceDetail").asText())
			.isEqualTo("ACCEPTED");
		assertThat(report.path("decisionId").asText()).isEqualTo(decision.decisionId());
		assertThat(report.path("sessionId").asText()).isEqualTo(decision.sessionId());
		assertThat(report.path("connectionId").asText()).isEqualTo(decision.connectionId().orElseThrow());
		assertThat(correlation(report.path("correlation"))).isEqualTo(expectedCorrelation());
		assertThat(report.path("usage")).as("native usage in the canonical export")
			.anySatisfy(usage -> c.usage
				.forEach((key, value) -> assertThat(usage.path(key).isInt() ? usage.path(key).asInt() : null).as(key)
					.isEqualTo(value)));
		assertThat(report.path("costMembers")).as("cost members in the canonical export").isEmpty();
	}

	private AgentExecutionProvider codex() throws IOException {
		Path child = copy("codex-child.py", "codex-child.py");
		copy("codex-native-template.jsonl", "codex-native-template.jsonl");
		return CodexClientProvider.builder()
			.codexPath(child.toString())
			.workingDirectory(this.root)
			.nativeSessionsDirectory(this.root.resolve("sessions"))
			.acquisitionDirectory(this.root.resolve("captures"), "0.166.0")
			.skipGitCheck(true)
			.build();
	}

	/**
	 * One-shot Claude capture reads native transcripts under user.home, which the build
	 * points at a fixture directory. The executable is a wrapper that tells the fixture
	 * where that is.
	 */
	private AgentExecutionProvider claude() throws IOException {
		Path child = copy("claude-one-shot-child.py", "claude-one-shot-child.py");
		Path projects = Path.of(System.getProperty("user.home"), ".claude", "projects");
		Files.createDirectories(projects);
		Path wrapper = this.root.resolve("claude");
		Files.writeString(wrapper,
				"#!/bin/sh\nexport FIXTURE_CLAUDE_PROJECTS='" + projects + "'\nexec python3 '" + child + "' \"$@\"\n",
				StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
		return ClaudeClientProvider.builder()
			.workingDirectory(this.root)
			.claudePath(wrapper.toString())
			.acquisitionDirectory(this.root.resolve("captures"), "2.1.316")
			.build();
	}

	private Path copy(String resource, String name) throws IOException {
		Path target = this.root.resolve(name);
		try (InputStream input = getClass().getResourceAsStream("/" + resource)) {
			assertThat(input).as(resource).isNotNull();
			Files.copy(input, target);
		}
		Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
		return target;
	}

	/**
	 * The separate reader JVM: Journal's capture TCK and its runtime dependencies only.
	 */
	static JsonNode read(Path store, String... selectors) throws Exception {
		Path report = Files.createTempDirectory(store.getParent(), "reader").resolve("report.json");
		String classpath = System.getProperty("conformance.reader.classes") + java.io.File.pathSeparator
				+ Files.readString(Path.of(System.getProperty("conformance.reader.classpath"))).trim();
		var command = new java.util.ArrayList<>(
				List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
						"io.github.markpollack.agents.conformance.reader.RecordingArchiveReader", store.toString(),
						report.toString()));
		command.addAll(List.of(selectors));
		var result = new ProcessExecutor().command(command)
			.readOutput(true)
			.redirectErrorStream(true)
			.timeout(60, TimeUnit.SECONDS)
			.exitValueAny()
			.execute();
		assertThat(result.getExitValue()).as(result.outputUTF8()).isZero();
		return JSON.readTree(report.toFile());
	}

	private static Map<String, String> correlation(JsonNode node) {
		var values = new LinkedHashMap<String, String>();
		node.fields()
			.forEachRemaining(e -> values.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
		return values;
	}

	private static Map<String, String> expectedCorrelation() {
		var c = CORRELATION;
		var values = new LinkedHashMap<String, String>();
		values.put("experimentId", c.experimentId().orElseThrow());
		values.put("experimentRunId", c.experimentRunId().orElseThrow());
		values.put("experimentInvocationId", c.experimentInvocationId().orElseThrow());
		values.put("workflowRunId", c.workflowRunId().orElseThrow());
		values.put("workflowInvocationId", c.workflowInvocationId().orElseThrow());
		values.put("workflowAttemptId", c.workflowAttemptId().orElseThrow());
		return values;
	}

	static void awaitDelivery(AgentExecution execution) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime() < end)
			Thread.sleep(5);
		assertThat(execution.recording().delivery()).as("recording delivery")
			.isInstanceOf(TerminalDelivery.Completed.class);
	}

}
