/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.client.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class ClaudeClientDirectoryTest {

	@TempDir
	Path root;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void explicitRequestWinsBeforeOneShotAcquisition(boolean async) throws Exception {
		Path initial = Files.createDirectory(root.resolve("A"));
		Path requested = Files.createDirectory(root.resolve("B"));
		try (var client = AgentClient.builder(provider(initial)).defaultWorkingDirectory(initial).build()) {
			var request = client.goal(new Goal("cwd-marker:request", root.resolve("unused-goal")))
				.workingDirectory(requested);
			if (async) {
				var execution = request.runAsync();
				assertThat(execution.result().isSuccessful()).isTrue();
				assertThat(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed()).isTrue();
			}
			else {
				assertThat(request.run().isSuccessful()).isTrue();
			}
		}
		assertThat(Files.readString(requested.resolve("request"))).isEqualTo(requested.toString());
		try (var files = Files.list(initial)) {
			assertThat(files).isEmpty();
		}
		assertDead(requested);
	}

	@Test
	void goalDirectoryWinsAndBuilderFallbackSurvivesGoalOptions() throws Exception {
		Path initial = Files.createDirectory(root.resolve("A"));
		Path goal = Files.createDirectory(root.resolve("B"));
		try (var client = AgentClient.builder(provider(Files.createDirectory(root.resolve("provider-home"))))
			.defaultWorkingDirectory(initial)
			.build()) {
			assertThat(client.goal(new Goal("cwd-marker:goal", goal)).run().isSuccessful()).isTrue();
			var options = DefaultAgentOptions.builder().model("fixture").build();
			assertThat(client.goal(new Goal("cwd-marker:fallback", null, options)).run().isSuccessful()).isTrue();
		}
		assertThat(Files.readString(goal.resolve("goal"))).isEqualTo(goal.toString());
		assertThat(Files.readString(initial.resolve("fallback"))).isEqualTo(initial.toString());
		assertThat(initial.resolve("goal")).doesNotExist();
		assertThat(goal.resolve("fallback")).doesNotExist();
		assertDead(initial);
		assertDead(goal);
	}

	@Test
	void retainedFollowupPreservesConfiguredHomeDespiteRequestDirectory() throws Exception {
		Path initial = Files.createDirectory(root.resolve("A"));
		Path home = Files.createDirectory(root.resolve("home"));
		Path context = Files.createDirectory(root.resolve("B"));
		try (var client = AgentClient.builder(provider(initial)).defaultWorkingDirectory(initial).build();
				var session = client.sessions().create(s -> s.workingDirectory(home))) {
			assertThat(session.goal("cwd-marker:first").run().isSuccessful()).isTrue();
			assertThat(session.goal("cwd-marker:followup").workingDirectory(context).run().isSuccessful()).isTrue();
		}
		assertThat(Files.readString(home.resolve("first"))).isEqualTo(home.toString());
		assertThat(Files.readString(home.resolve("followup"))).isEqualTo(home.toString());
		assertThat(Files.readAllLines(home.resolve("launches.jsonl"))).hasSize(1);
		try (var files = Files.list(context)) {
			assertThat(files).isEmpty();
		}
		try (var files = Files.list(initial)) {
			assertThat(files).isEmpty();
		}
		assertDead(home);
	}

	private ClaudeClientProvider provider(Path directory) throws Exception {
		Path script = root.resolve("child.py");
		try (var input = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(input, script);
		}
		Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		return ClaudeClientProvider.builder().workingDirectory(directory).claudePath(script.toString()).build();
	}

	private static void assertDead(Path directory) throws Exception {
		try (var files = Files.list(directory)) {
			for (Path file : files.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
				assertThat(ProcessHandle.of(Long.parseLong(Files.readString(file)))
					.map(ProcessHandle::isAlive)
					.orElse(false)).isFalse();
		}
	}

}
