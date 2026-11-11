/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.antigravity;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.*;

final class AntigravityTurn implements AgentExecutionProvider.Turn {

	private final AntigravityGeneration connection;

	private final String executionId;

	private boolean started;

	private volatile CaptureAcquisition acquisition = CaptureAcquisition.PENDING;

	private volatile AntigravityCapture capture;

	AntigravityTurn(AntigravityGeneration connection, String executionId) {
		this.connection = connection;
		this.executionId = executionId;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		synchronized (this) {
			if (this.started)
				throw new IllegalStateException("Antigravity turn cannot replay");
			this.started = true;
		}
		Instant start = Instant.now();
		try (this.connection) {
			return dispatch(request, start);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Antigravity interrupted", ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Antigravity execution failed", ex);
		}
	}

	private AgentResponse dispatch(AgentTaskRequest request, Instant start) throws Exception {
		if (!request.workingDirectory()
			.toAbsolutePath()
			.normalize()
			.equals(this.connection.options.getWorkingDirectory()))
			throw new IllegalArgumentException("Antigravity request directory differs from connection options");
		if (this.connection.configuration.capture() != null)
			this.capture = new AntigravityCapture(this.connection, this.executionId, request.goal());
		int exit = this.connection.process.run(request.goal().getBytes(StandardCharsets.UTF_8));
		this.connection.exitCode = exit;
		if (!this.connection.process.stdout().complete() || !this.connection.process.stderr().complete())
			throw new IllegalStateException("Incomplete Antigravity output streams");
		var result = AntigravityResult.parse(this.connection.process.stdout().bytes(),
				this.connection.process.stderr().bytes(), exit, Duration.between(start, Instant.now()));
		this.connection.nativeId = result.nativeId();
		return result.response(this.connection.options.getModel(), Duration.between(start, Instant.now()), exit,
				this.connection.id);
	}

	@Override
	public CaptureAcquisition captureAcquisition() {
		return this.connection.configuration.capture() == null ? CaptureAcquisition.UNAVAILABLE : this.acquisition;
	}

	/**
	 * The one-shot generation. Journal has no Antigravity qualifier, so a recorder
	 * reports it {@code UNQUALIFIED} and writes an evidence-only archive with no sources
	 * or usage. That archive does not carry the manifest's path or digest: the sealed
	 * manifest stays in this provider's acquisition directory, and the caller finds it
	 * through the execution's {@code recording()} capture reference.
	 */
	@Override
	public java.util.Optional<io.github.markpollack.agents.client.NativeGeneration> generation(
			io.github.markpollack.agents.client.ExecutionDecision decision) {
		if (this.capture == null)
			return java.util.Optional.empty();
		try {
			var sealed = this.capture.seal();
			this.acquisition = sealed.complete() ? CaptureAcquisition.COMPLETED : CaptureAcquisition.INCOMPLETE;
			var manifest = sealed.writeManifest(decision);
			return java.util.Optional
				.of(io.github.markpollack.agents.client.NativeGeneration.oneShot(AntigravityAcquisition.PROFILE,
						decision, manifest, io.github.markpollack.agents.client.NativeGeneration.sha256(manifest),
						sealed.complete(), sealed.detail() == null ? "" : sealed.detail()));
		}
		catch (java.io.IOException ex) {
			this.acquisition = CaptureAcquisition.FAILED;
			throw new java.io.UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			this.acquisition = CaptureAcquisition.FAILED;
			throw ex;
		}
	}

	@Override
	public Map<String, Object> recordingEvidence() {
		if (this.capture == null)
			return Map.of();
		try {
			var sealed = this.capture.seal();
			this.acquisition = sealed.complete() ? CaptureAcquisition.COMPLETED : CaptureAcquisition.INCOMPLETE;
			return Map.of("antigravityAcquisition", sealed);
		}
		catch (RuntimeException ex) {
			this.acquisition = CaptureAcquisition.FAILED;
			throw ex;
		}
	}

	@Override
	public void stop(boolean mayInterruptIfRunning) {
		this.connection.close();
	}

}
