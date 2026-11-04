/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import io.github.markpollack.agents.codexsdk.transport.CodexBytes;

/**
 * Acquires native and writes immutable originals only on the secondary recording lane.
 */
final class CodexCapture {

	private final CodexGeneration connection;

	private final String executionId;

	private CodexNativeSource nativeSource;

	private String inventoryFailure;

	private CodexAcquisition sealed;

	private final java.time.Instant start = java.time.Instant.now();

	private CodexNativeSource.Observed nativeObservation;

	private final CodexThread owner;

	private final CodexNativeSource.Prefix previous;

	CodexCapture(CodexGeneration connection, String executionId, CodexThread owner) {
		this.connection = connection;
		this.executionId = executionId;
		this.owner = owner;
		this.previous = owner.nativePrefix();
		try {
			this.nativeSource = new CodexNativeSource(connection.configuration.sessions(connection.options));
		}
		catch (IOException | java.io.UncheckedIOException ex) {
			this.inventoryFailure = ex.getMessage();
		}
	}

	synchronized CodexAcquisition seal() {
		if (this.sealed != null)
			return this.sealed;
		try {
			return sealSources();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Codex acquisition could not be sealed", ex);
		}
	}

	private CodexAcquisition sealSources() throws IOException {
		var process = this.connection.process;
		Path root = this.connection.configuration.capture().toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path directory = Files.createTempDirectory(root, "codex-");
		var sources = new ArrayList<CodexAcquisition.Source>();
		String detail = "NOT_DISPATCHED";
		boolean complete = false;
		if (process.dispatched()) {
			sources.add(write(directory, "stdin", process.stdin()));
			sources.add(write(directory, "stdout", process.stdout()));
			sources.add(write(directory, "stderr", process.stderr()));
			try {
				byte[] nativeBytes = acquireNative();
				sources.add(write(directory, "native", nativeBytes, nativeBytes.length, true, true));
				complete = process.terminated() && sources.stream().allMatch(CodexAcquisition.Source::complete);
				detail = complete ? "COMPLETE" : "INCOMPLETE_STREAMS_OR_CESSATION";
			}
			catch (IOException ex) {
				detail = ex.getMessage();
			}
		}
		this.sealed = new CodexAcquisition(this.executionId, this.connection.id,
				Optional.ofNullable(this.connection.nativeId), directory, sources, complete, detail,
				CodexManifest.template(
						new CodexManifest.Context(this.connection, this.executionId, this.start, this.previous),
						sources, this.nativeObservation, complete, detail));
		return this.sealed;
	}

	/**
	 * Fresh turn: new exact-thread file. Resumed turn: unchanged earlier prefix plus
	 * growth.
	 */
	private byte[] acquireNative() throws IOException {
		if (this.nativeSource == null)
			throw new IOException(this.inventoryFailure);
		Path cwd = this.connection.options.getWorkingDirectory();
		String version = this.connection.configuration.version();
		if (this.connection.resumeId == null)
			this.nativeObservation = this.nativeSource.read(this.connection.nativeId, cwd, version);
		else if (this.previous == null)
			throw new IOException("No sealed observation of the earlier turn's native prefix");
		else
			this.nativeObservation = this.nativeSource.readContinuation(this.previous, this.connection.nativeId, cwd,
					version);
		byte[] nativeBytes = this.nativeObservation.bytes();
		this.owner.nativePrefix(new CodexNativeSource.Prefix(this.nativeObservation.path(), nativeBytes));
		return nativeBytes;
	}

	private static CodexAcquisition.Source write(Path directory, String role, CodexBytes observed) throws IOException {
		return write(directory, role, observed.bytes(), observed.observed(), observed.complete(), observed.eof());
	}

	private static CodexAcquisition.Source write(Path directory, String role, byte[] bytes, long observed,
			boolean complete, boolean eof) throws IOException {
		Path file = directory.resolve(role + ".bin");
		Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
		try {
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			return new CodexAcquisition.Source(role, file, bytes.length, digest, observed, complete, eof);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
