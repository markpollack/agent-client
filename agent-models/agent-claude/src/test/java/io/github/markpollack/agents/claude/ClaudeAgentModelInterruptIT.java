package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClaudeAgentModel#interrupt()} stops a call that is already running, proven
 * against a real child process.
 *
 * <p>
 * ⚠️ An integration test, not a unit test, and deliberately so: it spawns a process and
 * its runtime is governed by the SDK transport's close path rather than by anything this
 * model does. See the steward's decision record — a stub CLI that never writes to stdout
 * exposes {@code StreamingTransport.close()} closing streams <em>before</em> destroying
 * the process, which can take minutes to return.
 *
 * <p>
 * An ACP cancel has to reach work in flight, and the reason it matters is the CLI's child
 * processes: without this they go on editing the project after the request is abandoned.
 * Before the change nothing could stop a call — each execution opened its client in local
 * scope, so no handle escaped and a blocked {@code receiveResponse()} had no other way
 * out.
 *
 * <p>
 * The CLI here is a stub script that blocks forever, pointed at through
 * {@code claudePath}, so no real agent CLI is discovered or invoked and the test costs
 * nothing. What it proves end-to-end is the part that matters: interrupting really does
 * destroy the process the model spawned.
 */
class ClaudeAgentModelInterruptIT {

	/** A "CLI" that prints nothing and never exits, until it is killed. */
	private Path blockingCli(Path dir) throws IOException {
		Path script = dir.resolve("blocking-cli.sh");
		Files.writeString(script, """
				#!/bin/sh
				# Announce our pid, then block as a single process. `exec` keeps this pid
				# and leaves no child, so the test measures the interrupt rather than a
				# shell's signal handling or a stray `sleep` holding the pipe open.
				echo "$$" > "$(dirname "$0")/pid"
				exec sleep 86400
				""");
		script.toFile().setExecutable(true);
		return script;
	}

	private boolean alive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	@Test
	void interruptEndsACallThatIsBlockedAndKillsItsProcess(@TempDir Path dir) throws Exception {
		Path cli = blockingCli(dir);
		Path pidFile = dir.resolve("pid");

		try (ClaudeAgentModel model = ClaudeAgentModel.builder()
			.claudePath(cli.toString())
			.workingDirectory(dir)
			.timeout(Duration.ofSeconds(90))
			.build()) {

			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch ended = new CountDownLatch(1);
			AtomicReference<AgentResponse> result = new AtomicReference<>();
			AtomicReference<Throwable> thrown = new AtomicReference<>();

			Thread caller = new Thread(() -> {
				started.countDown();
				try {
					result.set(model.call(AgentTaskRequest.builder("block", dir).build()));
				}
				catch (Throwable t) {
					thrown.set(t);
				}
				finally {
					ended.countDown();
				}
			}, "blocked-call");
			caller.setDaemon(true);
			caller.start();

			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

			// Wait for the stub CLI to actually be running before interrupting, so the
			// test exercises a call in flight rather than one that never started.
			long pid = -1;
			for (int i = 0; i < 100 && pid < 0; i++) {
				if (Files.exists(pidFile)) {
					String raw = Files.readString(pidFile).strip();
					if (!raw.isEmpty()) {
						pid = Long.parseLong(raw);
					}
				}
				if (pid < 0) {
					Thread.sleep(100);
				}
			}
			assertThat(pid).as("the stub CLI started and reported its pid").isGreaterThan(0);
			assertThat(alive(pid)).as("the CLI process is running before the interrupt").isTrue();

			// Without the fix this call returns immediately and the assertion below
			// times out: nothing reaches the blocked receiveResponse().
			model.interrupt();

			assertThat(ended.await(30, TimeUnit.SECONDS)).as("the blocked call ended after interrupt()").isTrue();

			// It ends through call()'s existing error path rather than by throwing —
			// that contract is deliberately unchanged, so callers keep their handling.
			assertThat(thrown.get()).isNull();
			assertThat(result.get()).isNotNull();

			// The point of the whole change: the child process is gone.
			for (int i = 0; i < 100 && alive(pid); i++) {
				Thread.sleep(100);
			}
			assertThat(alive(pid)).as("interrupt() destroyed the CLI process").isFalse();
		}
	}

}
