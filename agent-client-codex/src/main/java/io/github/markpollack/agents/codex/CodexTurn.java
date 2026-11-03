/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.*;

final class CodexTurn implements AgentExecutionProvider.Turn {

	private final CodexGeneration connection;

	private final String executionId;

	private boolean started;

	private volatile CaptureAcquisition acquisition = CaptureAcquisition.PENDING;

	private volatile CodexCapture capture;

	CodexTurn(CodexGeneration connection, String executionId) {
		this.connection = connection;
		this.executionId = executionId;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		synchronized (this) {
			if (this.started)
				throw new IllegalStateException("Codex turn cannot replay");
			this.started = true;
		}
		Instant start = Instant.now();
		try {
			return dispatch(request, start);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Codex interrupted", ex);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Codex execution failed", ex);
		}
		finally {
			this.connection.close();
		}
	}

	private AgentResponse dispatch(AgentTaskRequest request, Instant start) throws Exception {
		if (!request.workingDirectory()
			.toAbsolutePath()
			.normalize()
			.equals(this.connection.options.getWorkingDirectory()))
			throw new IllegalArgumentException("Codex request directory differs from connection options");
		if (this.connection.configuration.capture() != null)
			this.capture = new CodexCapture(this.connection, this.executionId);
		int exit = this.connection.process.run(request.goal().getBytes(StandardCharsets.UTF_8));
		this.connection.exitCode = exit;
		if (!this.connection.process.stdout().complete())
			throw new IllegalStateException("Incomplete Codex stdout");
		var result = CodexResult.parse(this.connection.process.stdout().bytes());
		this.connection.nativeId = result.nativeId();
		return result.response(this.connection.options.getModel(), Duration.between(start, Instant.now()), exit,
				this.connection.id);
	}

	@Override
	public CaptureAcquisition captureAcquisition() {
		return this.connection.configuration.capture() == null ? CaptureAcquisition.UNAVAILABLE : this.acquisition;
	}

	@Override
	public Map<String, Object> recordingEvidence() {
		if (this.capture == null)
			return Map.of();
		try {
			var sealed = this.capture.seal();
			this.acquisition = sealed.complete() ? CaptureAcquisition.COMPLETED : CaptureAcquisition.INCOMPLETE;
			return Map.of("codexAcquisition", sealed);
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
