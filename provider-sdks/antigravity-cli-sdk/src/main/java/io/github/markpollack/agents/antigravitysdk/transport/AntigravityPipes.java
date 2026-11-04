/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravitysdk.transport;

import java.io.*;
import org.zeroturnaround.exec.stream.ExecuteStreamHandler;

/** Own the original pipes and distinguish observed EOF from a forced reader close. */
final class AntigravityPipes implements ExecuteStreamHandler {

	final AntigravityBytes stdin = new AntigravityBytes();

	final AntigravityBytes stdout = new AntigravityBytes();

	final AntigravityBytes stderr = new AntigravityBytes();

	private final byte[] prompt;

	private OutputStream input;

	private InputStream output;

	private InputStream error;

	private Thread writer;

	private Thread reader;

	private Thread errorReader;

	private volatile boolean forced;

	AntigravityPipes(byte[] prompt) {
		this.prompt = prompt.clone();
	}

	@Override
	public void setProcessInputStream(OutputStream stream) {
		this.input = stream;
	}

	@Override
	public void setProcessOutputStream(InputStream stream) {
		this.output = stream;
	}

	@Override
	public void setProcessErrorStream(InputStream stream) {
		this.error = stream;
	}

	@Override
	public void start() {
		this.reader = Thread.ofVirtual().name("antigravity-stdout").start(() -> drain(this.output, this.stdout));
		this.errorReader = Thread.ofVirtual().name("antigravity-stderr").start(() -> drain(this.error, this.stderr));
		this.writer = Thread.ofVirtual().name("antigravity-stdin").start(this::write);
	}

	private void write() {
		try (var stream = this.input) {
			stream.write(this.prompt);
			stream.flush();
			this.stdin.accept(this.prompt, this.prompt.length);
			this.stdin.end(true);
		}
		catch (IOException ex) {
			this.stdin.fail();
		}
	}

	private void drain(InputStream input, AntigravityBytes destination) {
		try {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1)
				destination.accept(buffer, count);
			destination.end(!this.forced);
		}
		catch (IOException ex) {
			destination.fail();
		}
	}

	@Override
	public void stop() {
		join(100);
	}

	void finish() {
		join(1000);
		if (!stopped()) {
			this.forced = true;
			close(this.input);
			close(this.output);
			close(this.error);
			join(200);
		}
	}

	boolean stopped() {
		return dead(this.writer) && dead(this.reader) && dead(this.errorReader);
	}

	private static boolean dead(Thread thread) {
		return thread == null || !thread.isAlive();
	}

	private void join(long millis) {
		join(this.writer, millis);
		join(this.reader, millis);
		join(this.errorReader, millis);
	}

	private static void join(Thread thread, long millis) {
		if (thread == null)
			return;
		try {
			thread.join(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void close(Closeable stream) {
		if (stream == null)
			return;
		try {
			stream.close();
		}
		catch (IOException ignored) {
			/* Unconfirmed EOF remains explicit. */ }
	}

}
