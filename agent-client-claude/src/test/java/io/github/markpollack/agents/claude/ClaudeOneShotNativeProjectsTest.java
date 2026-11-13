/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.NativeGeneration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One-shot acquisition when the CLI runs with a different home than this JVM: the exact
 * session's transcript is found under the configured sandbox projects root, or reported
 * unavailable. This JVM's {@code user.home} points at a separate parent home for every
 * test, so nothing reads the real home.
 */
@Timeout(30)
class ClaudeOneShotNativeProjectsTest {

	/** The session the fixture child reports. */
	private static final String SESSION = "fixture-history";

	private static final String SENTINEL = "SENTINEL-CREDENTIAL-a1b2c3";

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path root;

	private Path author;

	private Path sandboxHome;

	private Path sandboxProjects;

	private Path parentProjects;

	private String savedHome;

	@BeforeEach
	void layout() throws Exception {
		Path parentHome = this.root.resolve("parent-home");
		this.parentProjects = Files.createDirectories(parentHome.resolve(".claude").resolve("projects"));
		this.author = Files.createDirectory(this.root.resolve("author"));
		this.sandboxHome = this.author.resolve("home");
		this.sandboxProjects = Files.createDirectories(this.sandboxHome.resolve(".claude").resolve("projects"));
		this.savedHome = System.getProperty("user.home");
		System.setProperty("user.home", parentHome.toString());
	}

	@AfterEach
	void restoreHome() {
		System.setProperty("user.home", this.savedHome);
	}

	@Test
	void sandboxTranscriptIsAcquiredUnchangedFromTheConfiguredRoot() throws Exception {
		childWritesUnder(this.sandboxProjects);
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		Path original = this.sandboxProjects.resolve("project").resolve(SESSION + ".jsonl");
		var manifest = manifest(generation);
		var transcript = transcript(manifest);
		assertThat(manifest.path("capture_errors")).isEmpty();
		assertThat(generation.complete()).isTrue();
		assertThat(transcript.path("state").asText()).isEqualTo("acquired");
		assertThat(transcript.path("origin").asText()).isEqualTo(original.toString());
		Path packaged = generation.manifest().resolveSibling(transcript.path("path").asText());
		assertThat(transcript.path("sha256").asText()).isEqualTo(sha256(original)).isEqualTo(sha256(packaged));
		assertThat(Files.mismatch(original, packaged)).isEqualTo(-1L);
		var provenance = manifest.path("run").path("launch_settings").path("artifact_provenance");
		assertThat(provenance.path("native_projects_root").asText()).isEqualTo(this.sandboxProjects.toString());
		assertThat(provenance.path("host_launch_directory").asText()).isEqualTo(this.author.toString());
		assertThat(provenance.has("native_cwd")).isFalse();
		assertThat(manifest.path("run").path("launch_settings").path("cwd").asText()).isEqualTo(this.author.toString());
	}

	@Test
	void withoutTheRootTheSandboxTranscriptIsNotAcquired() throws Exception {
		childWritesUnder(this.sandboxProjects);
		var generation = run(UnaryOperator.identity());
		assertThat(this.sandboxProjects.resolve("project").resolve(SESSION + ".jsonl")).isRegularFile();
		var manifest = manifest(generation);
		assertUnavailable(generation, "Exact session lookup yielded 0 files");
		assertThat(manifest.path("run").path("launch_settings").path("artifact_provenance").isEmpty()).isTrue();
	}

	@Test
	void unsetRootIsTheParentHomesProjectsDirectory() {
		var config = new ClaudeClientConfiguration(this.author, null, null, this.root.resolve("captures"), "2.1.316",
				null, List.of(), null, null, null);
		var options = ClaudeClientSources.captureOptions(config, this.author, "execution");
		assertThat(options.nativeProjectsDirectory())
			.isEqualTo(Path.of(System.getProperty("user.home"), ".claude", "projects"));
		assertThat(options.provenance()).isEmpty();
	}

	@Test
	void missingTranscriptIsUnavailable() throws Exception {
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertUnavailable(generation, "Exact session lookup yielded 0 files");
	}

	@Test
	void transcriptOnlyUnderTheParentHomeIsNotUsedWhenARootIsConfigured() throws Exception {
		childWritesUnder(this.parentProjects);
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertThat(this.parentProjects.resolve("project").resolve(SESSION + ".jsonl")).isRegularFile();
		assertUnavailable(generation, "Exact session lookup yielded 0 files");
	}

	@Test
	void anotherSessionsTranscriptIsNotAcquired() throws Exception {
		writeTranscript(this.sandboxProjects.resolve("project"), "other-session", "other-session");
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertUnavailable(generation, "Exact session lookup yielded 0 files");
	}

	@Test
	void sameSessionInTwoProjectsIsUnavailable() throws Exception {
		childWritesUnder(this.sandboxProjects);
		writeTranscript(this.sandboxProjects.resolve("other-project"), SESSION, SESSION);
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertUnavailable(generation, "Exact session lookup yielded 2 files");
	}

	@Test
	void sameSessionAtTwoDepthsIsUnavailable() throws Exception {
		childWritesUnder(this.sandboxProjects);
		writeTranscript(this.sandboxProjects, SESSION, SESSION);
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertUnavailable(generation, "Exact session lookup yielded 2 files");
	}

	@Test
	void fileNamedForTheSessionButRecordingAnotherIsRefused() throws Exception {
		Path impostor = writeTranscript(this.sandboxProjects.resolve("project"), SESSION, "other-session");
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		String refusal = "java.io.IOException: Native session identity mismatch: " + impostor;
		var manifest = manifest(generation);
		assertThat(generation.complete()).isFalse();
		assertThat(texts(manifest.path("capture_errors"))).contains(refusal, "Native transcript unavailable");
		assertThat(transcript(manifest).path("state").asText()).isEqualTo("missing");
		assertThat(transcript(manifest).path("reason").asText()).isEqualTo(refusal);
		assertThat(generation.manifest().resolveSibling("native")).doesNotExist();
	}

	@Test
	void rootThatIsARegularFileIsUnavailable() throws Exception {
		Path file = Files.writeString(this.root.resolve("projects-file"), "not a directory");
		var generation = run(b -> b.oneShotNativeProjects(file));
		assertUnavailable(generation, "Native projects directory unavailable");
	}

	@Test
	void rootThatDoesNotExistIsUnavailable() throws Exception {
		var generation = run(b -> b.oneShotNativeProjects(this.root.resolve("absent")));
		assertUnavailable(generation, "Native projects directory unavailable");
	}

	@Test
	void credentialsBesideTheTranscriptAreNeverPackaged() throws Exception {
		childWritesUnder(this.sandboxProjects);
		for (Path dir : List.of(this.sandboxHome.resolve(".claude"), this.sandboxProjects,
				this.sandboxProjects.resolve("project")))
			Files.writeString(dir.resolve(".credentials.json"), "{\"token\":\"" + SENTINEL + "\"}");
		var generation = run(b -> b.oneShotNativeProjects(this.sandboxProjects));
		assertThat(generation.complete()).isTrue();
		try (var files = Files.walk(this.root.resolve("captures"))) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				assertThat(file.getFileName().toString()).isNotEqualTo(".credentials.json");
				assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(SENTINEL);
			}
		}
	}

	@Test
	void rootRequiresAnAcquisitionDirectory() {
		assertThatThrownBy(() -> ClaudeClientProvider.builder().oneShotNativeProjects(this.sandboxProjects).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("A one-shot native projects root requires an acquisition directory");
		assertThat(ClaudeClientProvider.builder()
			.acquisitionDirectory(this.root.resolve("captures"), "2.1.316")
			.oneShotNativeProjects(this.sandboxProjects)
			.freshTranscript(this.sandboxProjects)
			.build()).isNotNull();
	}

	/**
	 * The fixture child writes its session file under {@code native-projects/project} of
	 * its working directory when that directory exists; link it to the given root.
	 */
	private void childWritesUnder(Path projects) throws Exception {
		Files.createDirectories(projects.resolve("project"));
		Files.createSymbolicLink(this.author.resolve("native-projects"), projects);
	}

	private static Path writeTranscript(Path project, String name, String session) throws Exception {
		Files.createDirectories(project);
		return Files.writeString(project.resolve(name + ".jsonl"),
				"{\"type\":\"user\",\"sessionId\":\"" + session + "\",\"uuid\":\"u1\",\"parentUuid\":null,"
						+ "\"message\":{\"role\":\"user\",\"content\":\"seeded\"}}\n");
	}

	private NativeGeneration run(UnaryOperator<ClaudeClientProvider.Builder> configure) throws Exception {
		Path script = this.root.resolve("child.py");
		try (var source = getClass().getResourceAsStream("/retained-child.py")) {
			Files.copy(source, script);
		}
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		var builder = ClaudeClientProvider.builder()
			.workingDirectory(this.author)
			.claudePath(script.toString())
			.acquisitionDirectory(this.root.resolve("captures"), "2.1.316");
		var probe = new GenerationProbe();
		try (var client = AgentClient.builder(configure.apply(builder).build())
			.defaultWorkingDirectory(this.author)
			.executionRecorder(probe)
			.build()) {
			var execution = client.goal("one shot").runAsync();
			assertThat(execution.result().isSuccessful()).isTrue();
			return probe.sealed.get(5, TimeUnit.SECONDS).orElseThrow();
		}
	}

	private static void assertUnavailable(NativeGeneration generation, String reason) throws Exception {
		var manifest = manifest(generation);
		assertThat(generation.complete()).isFalse();
		assertThat(texts(manifest.path("capture_errors"))).containsExactly("Native transcript unavailable");
		assertThat(transcript(manifest).path("state").asText()).isEqualTo("missing");
		assertThat(transcript(manifest).path("reason").asText()).isEqualTo(reason);
		assertThat(generation.manifest().resolveSibling("native")).doesNotExist();
	}

	private static JsonNode manifest(NativeGeneration generation) throws Exception {
		return JSON.readTree(generation.manifest().toFile());
	}

	private static JsonNode transcript(JsonNode manifest) {
		JsonNode found = null;
		for (JsonNode source : manifest.path("sources"))
			if ("transcript".equals(source.path("role").asText())) {
				assertThat(found).as("one transcript source").isNull();
				found = source;
			}
		assertThat(found).as("transcript source").isNotNull();
		return found;
	}

	private static List<String> texts(JsonNode array) {
		var values = new java.util.ArrayList<String>();
		array.forEach(value -> values.add(value.asText()));
		return values;
	}

	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}

}
