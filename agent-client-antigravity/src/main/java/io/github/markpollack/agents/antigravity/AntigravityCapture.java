/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import io.github.markpollack.agents.antigravitysdk.transport.AntigravityBytes;

final class AntigravityCapture {

	private final AntigravityGeneration generation;

	private final String executionId;

	private final String prompt;

	private AntigravityAcquisition sealed;

	AntigravityCapture(AntigravityGeneration generation, String executionId, String prompt) {
		this.generation = generation;
		this.executionId = executionId;
		this.prompt = prompt;
	}

	synchronized AntigravityAcquisition seal() {
		if (this.sealed != null)
			return this.sealed;
		try {
			return sealSources();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Antigravity acquisition could not be sealed", ex);
		}
	}

	private AntigravityAcquisition sealSources() throws IOException {
		Path root = this.generation.configuration.capture().toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path directory = Files.createTempDirectory(root, "antigravity-");
		var sources = new ArrayList<AntigravityAcquisition.Source>();
		var process = this.generation.process;
		if (process.dispatched()) {
			sources.add(write(directory, "stdin", process.stdin()));
			sources.add(write(directory, "stdout", process.stdout()));
			sources.add(write(directory, "stderr", process.stderr()));
			byte[] bytes = this.prompt.getBytes(StandardCharsets.UTF_8);
			sources.add(write(directory, "prompt-argument", bytes, bytes.length, true, false));
		}
		boolean complete = process.dispatched() && process.terminated() && this.generation.nativeId != null
				&& sources.stream().allMatch(AntigravityAcquisition.Source::complete);
		this.sealed = new AntigravityAcquisition(this.executionId, this.generation.id,
				Optional.ofNullable(this.generation.nativeId), directory, this.generation.configuration.version(),
				sources, complete,
				complete ? "COMPLETE_STREAM_CAPTURE_ROUTE_UNQUALIFIED" : "INCOMPLETE_STREAM_IDENTITY_OR_CESSATION");
		return this.sealed;
	}

	private static AntigravityAcquisition.Source write(Path directory, String role, AntigravityBytes bytes)
			throws IOException {
		return write(directory, role, bytes.bytes(), bytes.observed(), bytes.complete(), bytes.eof());
	}

	private static AntigravityAcquisition.Source write(Path directory, String role, byte[] bytes, long observed,
			boolean complete, boolean eof) throws IOException {
		Path file = directory.resolve(role + ".bin");
		Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
		try {
			return new AntigravityAcquisition.Source(role, file, bytes.length,
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), observed, complete,
					eof);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
