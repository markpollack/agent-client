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

	private final CodexThread owner;

	private volatile boolean started;

	private volatile CaptureAcquisition acquisition = CaptureAcquisition.PENDING;

	private volatile CodexCapture capture;

	CodexTurn(CodexGeneration connection, String executionId, CodexThread owner) {
		this.connection = connection;
		this.executionId = executionId;
		this.owner = owner;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		startOnce();
		String unsafe = "turn did not reach a parsed terminal";
		try (this.connection) {
			var response = dispatch(request, Instant.now());
			unsafe = successful(response) ? null : "native terminal was not a successful turn.completed";
			return response;
		}
		catch (Exception ex) {
			unsafe = "turn failed: " + ex.getMessage();
			throw failure(ex);
		}
		finally {
			settle(unsafe);
		}
	}

	private synchronized void startOnce() {
		if (this.started)
			throw new IllegalStateException("Codex turn cannot replay");
		this.started = true;
	}

	private boolean successful(AgentResponse response) {
		Integer exit = this.connection.exitCode;
		return exit != null && exit == 0 && "turn.completed".equals(response.getMetadata().get("nativeReason"));
	}

	private static IllegalStateException failure(Exception ex) {
		if (ex instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			return new IllegalStateException("Codex interrupted", ex);
		}
		return new IllegalStateException("Codex execution failed", ex);
	}

	/** Any turn without a parsed successful terminal leaves the thread history unsafe. */
	private void settle(String unsafe) {
		if (unsafe != null)
			this.owner.quarantine(unsafe);
	}

	private AgentResponse dispatch(AgentTaskRequest request, Instant start) throws Exception {
		if (!request.workingDirectory()
			.toAbsolutePath()
			.normalize()
			.equals(this.connection.options.getWorkingDirectory()))
			throw new IllegalArgumentException("Codex request directory differs from connection options");
		if (this.connection.configuration.capture() != null) {
			this.capture = new CodexCapture(this.connection, this.executionId, this.owner);
			this.connection.capture = this.capture;
			this.connection.configuration.observe(this.capture);
		}
		int exit = this.connection.process.run(request.goal().getBytes(StandardCharsets.UTF_8));
		this.connection.exitCode = exit;
		if (!this.connection.process.stdout().complete())
			throw new IllegalStateException("Incomplete Codex stdout");
		var result = CodexResult.parse(this.connection.process.stdout().bytes(), this.connection.resumeId);
		this.connection.nativeId = result.nativeId();
		this.connection.identitySource = result.identitySource();
		this.connection.wireUsage = result.usage();
		this.owner.observeIdentity(result.nativeId());
		return result.response(this.connection.options.getModel(), Duration.between(start, Instant.now()), exit,
				this.connection);
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

	/**
	 * The Client lifecycle stops a turn only for a cancel or deadline it decided. Even if
	 * the process had already completed, the caller never accepted that result, so the
	 * thread history is unsafe to resume: quarantine first, then close the process.
	 */
	@Override
	public void stop(boolean mayInterruptIfRunning) {
		if (this.started)
			this.owner.quarantine("turn stopped by the Client lifecycle before its result was accepted");
		this.connection.close();
	}

}
