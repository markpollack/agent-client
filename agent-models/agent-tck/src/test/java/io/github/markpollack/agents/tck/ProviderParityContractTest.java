/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.sandbox.LocalSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs both inherited contract suites locally and verifies their rejection paths. */
class ProviderParityContractTest extends ProviderParityTCK {

	private String provider = Provider.CODEX;

	@BeforeEach
	void configureTestDouble() {
		this.agentModel = new FileTaskDouble();
		this.sandbox = new LocalSandbox(this.tempDir);
	}

	@Override
	protected String getProvider() {
		return this.provider;
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void complexTaskClosesWorkspaceDirectoryHandles() throws IOException {
		long before = openWorkspaceDescriptors();
		try (var openDirectory = Files.list(tempDir)) {
			assertThat(openWorkspaceDescriptors()).isGreaterThan(before);
		}
		assertThat(openWorkspaceDescriptors()).isEqualTo(before);
		testComplexTask();
		assertThat(openWorkspaceDescriptors()).isEqualTo(before);
	}

	private long openWorkspaceDescriptors() throws IOException {
		try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
			return descriptors.filter(descriptor -> {
				try {
					return Files.readSymbolicLink(descriptor).startsWith(tempDir);
				}
				catch (IOException ex) {
					return false;
				}
			}).count();
		}
	}

	@Test
	void complexTaskDoesNotWriteToStandardOutput() throws IOException {
		var output = new ByteArrayOutputStream();
		PrintStream original = System.out;
		try (var captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
			System.setOut(captured);
			try {
				testComplexTask();
			}
			finally {
				System.setOut(original);
			}
		}
		assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	@Test
	void rejectsSuccessfulResponseWithoutRequestedFile() {
		this.agentModel = request -> response("Done");
		assertThatThrownBy(this::testSimpleFileCreation).isInstanceOf(AssertionError.class);
	}

	@Test
	void rejectsWrongFileContent() {
		this.agentModel = request -> {
			write(request.workingDirectory().resolve("greeting.txt"), "Wrong greeting");
			return response("Done");
		};
		assertThatThrownBy(this::testSimpleFileCreation).isInstanceOf(AssertionError.class);
	}

	@Test
	void rejectsListingThatDoesNotMentionTheFile() {
		this.agentModel = request -> response("No files");
		assertThatThrownBy(this::testFileListAccess).isInstanceOf(AssertionError.class);
	}

	@Test
	void rejectsUnfixedCode() {
		this.agentModel = request -> response("Done");
		assertThatThrownBy(this::testCodeFixTask).isInstanceOf(AssertionError.class);
	}

	@Test
	void rejectsComplexTaskWithNoCreatedArtifacts() {
		this.agentModel = request -> response("Done");
		assertThatThrownBy(this::testComplexTask).isInstanceOf(AssertionError.class);
	}

	@Test
	void rejectsUnavailableProvider() {
		this.agentModel = new FileTaskDouble() {
			@Override
			public boolean isAvailable() {
				return false;
			}
		};
		assertThatThrownBy(this::testAgentAvailability).isInstanceOf(AssertionError.class);
	}

	@Test
	void abortsUnsupportedProviderScenario() throws Exception {
		this.provider = "EXTERNAL_PROVIDER";
		Method method = ProviderParityTCK.class.getDeclaredMethod("testSimpleFileCreationInNonGitDirectory");
		assertThatThrownBy(() -> checkProviderCapability(new MethodTestInfo(method)))
			.isInstanceOf(TestAbortedException.class)
			.hasMessageContaining("NOT_APPLICABLE");
	}

	@Test
	void closesSandboxAndAllowsRepeatedCleanup() throws Exception {
		tearDown();
		assertThat(this.sandbox.isClosed()).isTrue();
		tearDown();
		this.sandbox = null;
		tearDown();
	}

	private static AgentResponse response(String output) {
		return new AgentResponse(List.of(new AgentGeneration(output)),
				new AgentResponseMetadata("test-double", Duration.ofMillis(1), "", Map.of()));
	}

	private static void write(Path file, String contents) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, contents);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static class FileTaskDouble implements AgentApi {

		private static final Pattern CREATE_FILE = Pattern
			.compile("Create a file named '([^']+)' with the content '([^']+)'");

		@Override
		public AgentResponse call(AgentTaskRequest request) {
			String goal = request.goal();
			Path directory = request.workingDirectory();
			var create = CREATE_FILE.matcher(goal);
			if (create.matches()) {
				write(directory.resolve(create.group(1)), create.group(2));
			}
			else if (goal.equals("List the files in the working directory and read their contents")) {
				try (var files = Files.list(directory)) {
					return response(String.join("\n", files.map(path -> path.getFileName().toString()).toList()));
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			else if (goal.equals("Fix the syntax error in Broken.java")) {
				write(directory.resolve("Broken.java"),
						"public class Broken { public static void main(String[] args) { System.out.println(\"Hello World\"); } }");
			}
			else if (goal.equals("Create 100 files with different names and contents")) {
				for (int i = 0; i < 100; i++) {
					write(directory.resolve("file-" + i + ".txt"), "Content " + i);
				}
			}
			else if (goal.startsWith("Create a directory called 'project',")) {
				write(directory.resolve("project/README.md"), "Test project");
				write(directory.resolve("project/main.py"), "print('Hello from complex task')");
			}
			else {
				throw new IllegalArgumentException("Unexpected contract task: " + goal);
			}
			return response("Task completed");
		}

	}

	private record MethodTestInfo(Method method) implements TestInfo {

		@Override
		public String getDisplayName() {
			return this.method.getName();
		}

		@Override
		public Set<String> getTags() {
			return Set.of();
		}

		@Override
		public Optional<Class<?>> getTestClass() {
			return Optional.of(this.method.getDeclaringClass());
		}

		@Override
		public Optional<Method> getTestMethod() {
			return Optional.of(this.method);
		}

	}

}
