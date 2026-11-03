/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codexsdk.transport;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.agents.codexsdk.types.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class CodexProcessTest {

	@TempDir
	Path directory;

	private CodexProcess process(String body, Duration timeout) throws Exception {
		var script = directory.resolve("child.py");
		Files.writeString(script, "#!/usr/bin/python3\nimport os,sys,time,pathlib,signal,subprocess\n" + body);
		Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		return new CodexProcess(script.toString(),
				ExecuteOptions.builder()
					.workingDirectory(directory)
					.timeout(timeout)
					.fullAuto(false)
					.sandboxMode(SandboxMode.READ_ONLY)
					.jsonOutput(true)
					.build());
	}

	@Test
	void exactArgvRawBytesNaturalEofAndNoReplay() throws Exception {
		try (var p = process(
				"sys.stdout.buffer.write(sys.stdin.buffer.read())\nsys.stderr.buffer.write(b'\\xffSECRET')\n",
				Duration.ofSeconds(3))) {
			assertThat(p.argv())
				.containsSubsequence("--no-daemon", "--ask-for-approval", "never", "--sandbox", "read-only")
				.containsSubsequence("exec", "--ignore-user-config")
				.endsWith("--", "-");
			assertThat(p.run(new byte[] { 0, (byte) 0xff, 10 })).isZero();
			assertThat(p.stdin().bytes()).containsExactly(0, (byte) 0xff, 10);
			assertThat(p.stdout().bytes()).containsExactly(0, (byte) 0xff, 10);
			assertThat(p.stderr().bytes()).containsExactly((byte) 0xff, 83, 69, 67, 82, 69, 84);
			assertThat(p.stdout().complete()).isTrue();
			assertThat(p.stderr().complete()).isTrue();
			assertThat(p.terminated()).isTrue();
			assertThat(p.observedPids()).hasSize(1);
			assertThatThrownBy(() -> p.run(new byte[0])).isInstanceOf(CancellationException.class);
		}
	}

	@Test
	void launchFailureKeepsCleanupFailureSuppressed() throws Exception {
		var p = org.mockito.Mockito.spy(new CodexProcess(directory.resolve("absent").toString(),
				ExecuteOptions.builder().workingDirectory(directory).build()));
		var cleanup = failAfterRealClose(p);
		assertThatThrownBy(() -> p.run(new byte[0])).isInstanceOf(java.io.IOException.class)
			.hasSuppressedException(cleanup);
		assertThat(p.terminated()).isTrue();
	}

	@Test
	void transportTimeoutKeepsCleanupFailureSuppressed() throws Exception {
		var p = org.mockito.Mockito.spy(process("sys.stdin.buffer.read()\ntime.sleep(60)\n", Duration.ofMillis(150)));
		var cleanup = failAfterRealClose(p);
		assertThatThrownBy(() -> p.run(new byte[0])).isInstanceOf(TimeoutException.class)
			.hasSuppressedException(cleanup);
		assertThat(p.terminated()).isTrue();
	}

	@Test
	void successfulExecutionStillReportsCleanupFailure() throws Exception {
		var p = org.mockito.Mockito.spy(process("sys.stdin.buffer.read()\n", Duration.ofSeconds(2)));
		var cleanup = failAfterRealClose(p);
		assertThatThrownBy(() -> p.run(new byte[0])).isSameAs(cleanup);
		assertThat(p.terminated()).isTrue();
	}

	private static RuntimeException failAfterRealClose(CodexProcess process) {
		var failure = new IllegalStateException("injected cleanup failure");
		org.mockito.Mockito.doAnswer(call -> {
			call.callRealMethod();
			throw failure;
		}).when(process).close();
		return failure;
	}

	@Test
	void stoppedBeforeLaunchCannotCreateProcess() throws Exception {
		var p = process("pathlib.Path('ran').touch()\n", Duration.ofSeconds(1));
		p.close();
		assertThatThrownBy(() -> p.run(new byte[0])).isInstanceOf(CancellationException.class);
		assertThat(p.dispatched()).isFalse();
		assertThat(p.terminated()).isTrue();
		assertThat(directory.resolve("ran")).doesNotExist();
	}

	@Test
	void deadlineCleansObservedChildAndDescendant() throws Exception {
		var p = process(
				"child=subprocess.Popen(['/bin/sleep','60'])\nsignal.signal(signal.SIGTERM,lambda *_:(child.terminate(),child.wait(),sys.exit(0)))\nsys.stdin.buffer.read()\ntime.sleep(60)\n",
				Duration.ofMillis(300));
		assertThatThrownBy(() -> p.run(new byte[] { 1 })).isInstanceOf(TimeoutException.class);
		assertThat(p.observedPids()).hasSize(2);
		assertThat(p.terminated()).isTrue();
		for (long pid : p.observedPids())
			assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
		p.close();
	}

	@Test
	void boundedBytesKeepOverflowAndFailureExplicit() {
		var bytes = new CodexBytes();
		byte[] large = new byte[4 * 1024 * 1024 + 17];
		bytes.accept(large, large.length);
		bytes.end(true);
		assertThat(bytes.observed()).isEqualTo(large.length);
		assertThat(bytes.bytes()).hasSize(4 * 1024 * 1024);
		assertThat(bytes.eof()).isTrue();
		assertThat(bytes.complete()).isFalse();
		var failed = new CodexBytes();
		failed.end(true);
		failed.fail();
		assertThat(failed.complete()).isFalse();
	}

	@Test
	void failedLaunchRetainsClosedOwnership() {
		var p = new CodexProcess(directory.resolve("absent").toString(),
				ExecuteOptions.builder().workingDirectory(directory).build());
		assertThatThrownBy(() -> p.run(new byte[0])).isInstanceOf(java.io.IOException.class);
		assertThat(p.terminated()).isTrue();
		assertThat(p.dispatched()).isFalse();
	}

}
