/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codexsdk.transport;

import java.io.ByteArrayOutputStream;

/** Bounded original stream bytes, before decoding or redaction. */
public final class CodexBytes {

	private static final int LIMIT = 4 * 1024 * 1024;

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

	private long observed;

	private boolean eof;

	private boolean failed;

	synchronized void accept(byte[] value, int length) {
		this.observed += length;
		int retained = Math.min(length, LIMIT - this.bytes.size());
		this.bytes.write(value, 0, retained);
	}

	synchronized void end(boolean natural) {
		this.eof = natural;
	}

	synchronized void fail() {
		this.failed = true;
	}

	public synchronized byte[] bytes() {
		return this.bytes.toByteArray();
	}

	public synchronized long observed() {
		return this.observed;
	}

	public synchronized boolean eof() {
		return this.eof;
	}

	public synchronized boolean complete() {
		return this.eof && !this.failed && this.observed == this.bytes.size();
	}

}
