/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.geminisdk.transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import io.github.markpollack.agents.geminisdk.exceptions.GeminiSDKException;
import io.github.markpollack.agents.geminisdk.util.GeminiCliDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.zeroturnaround.exec.ProcessExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CLITransportExecutionTest {

	@TempDir
	Path tempDir;

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void nvmExecutionPassesTheCliScriptExactlyOnce() throws Exception {
		Path bin = Files.createDirectories(tempDir.resolve(".nvm/versions/node/v22.0.0/bin"));
		Path node = bin.resolve("node");
		Files.writeString(node, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$0.args\"\nprintf 'ok\\n'\n");
		Files.setPosixFilePermissions(node, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE));
		Path gemini = bin.resolve("gemini");
		CLITransport transport = transportFor(gemini.toString());
		CLIOptions options = CLIOptions.builder().yoloMode(false).timeout(Duration.ofSeconds(5)).build();
		assertThat(transport.executeQuery("hello", options)).isNotEmpty();
		assertThat(Files.readAllLines(bin.resolve("node.args"))).containsExactly(gemini.toString(), "-p", "hello");
	}

	@Test
	void interruptionRestoresTheCallingThreadsInterruptFlag() throws Exception {
		CLITransport transport = transportFor("gemini");
		try (var processes = mockConstruction(ProcessExecutor.class, withSettings().defaultAnswer(RETURNS_SELF),
				(mock, context) -> when(mock.execute()).thenThrow(new InterruptedException("test interruption")))) {
			assertThatThrownBy(() -> transport.executeQuery("hello", CLIOptions.builder().build()))
				.isInstanceOf(GeminiSDKException.class)
				.hasCauseInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	private CLITransport transportFor(String command) {
		try (var discovery = mockStatic(GeminiCliDiscovery.class)) {
			discovery.when(GeminiCliDiscovery::findGeminiCommand).thenReturn(command);
			return new CLITransport(tempDir, Duration.ofSeconds(5));
		}
	}

}
