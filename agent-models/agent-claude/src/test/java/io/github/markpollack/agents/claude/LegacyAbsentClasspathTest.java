/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Journal 1.x is optional: with a class path that truly lacks journal-core and
 * claude-code-capture, Boot auto-configuration starts, ordinary calls work (without
 * phaseCapture), and a legacy tracing request fails with a diagnostic that names both
 * artifacts.
 */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class LegacyAbsentClasspathTest {

	@TempDir
	Path temp;

	@Test
	void modernPathsWorkWithoutJournalOneAndLegacyRequestsNameBothArtifacts() throws Exception {
		Path fake = fakeClaude("session-x");
		List<URL> urls = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		for (String entry : classPath.split(java.io.File.pathSeparator)) {
			String name = Path.of(entry).getFileName().toString();
			if (name.startsWith("journal-core-") || name.startsWith("claude-code-capture-")) {
				excluded.add(name);
				continue;
			}
			urls.add(Path.of(entry).toUri().toURL());
		}
		assertThat(excluded).as("the two legacy artifacts were present on the test class path and are now excluded")
			.hasSize(2);
		var previous = Thread.currentThread().getContextClassLoader();
		try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
			assertThatThrownBy(() -> loader.loadClass("io.github.markpollack.journal.claude.SessionLogParser"))
				.isInstanceOf(ClassNotFoundException.class);
			Thread.currentThread().setContextClassLoader(loader);
			Class<?> probe = loader.loadClass("io.github.markpollack.agents.claude.LegacyAbsentProbe");
			@SuppressWarnings("unchecked")
			List<String> facts = (List<String>) probe.getMethod("run", String.class, String.class)
				.invoke(null, fake.toString(), temp.toString());
			assertThat(facts).contains("journal:absent");
			assertThat(facts).anySatisfy(f -> assertThat(f).startsWith("boot:ok:ClaudeAgentModel"));
			assertThat(facts).anySatisfy(f -> assertThat(f).startsWith("boot-trace:")
				.contains("io.github.markpollack:journal-core:1.10.0",
						"io.github.markpollack:claude-code-capture:1.10.0"));
			assertThat(facts).anySatisfy(f -> assertThat(f).startsWith("builder-trace:")
				.contains("journal-core:1.10.0", "claude-code-capture:1.10.0", "legacy-tracing-migration"));
			assertThat(facts).contains("call:sentinel-answer:phaseCapture=null:finish=SUCCESS");
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	private Path fakeClaude(String session) throws Exception {
		Path executable = temp.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/bin/sh
						echo '{"type":"system","subtype":"init","session_id":"%s"}'
						IFS= read -r line
						echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"result":"sentinel-answer","session_id":"%s","usage":{"input_tokens":1,"output_tokens":1}}'
						"""
					.formatted(session, session));
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		return executable;
	}

}
