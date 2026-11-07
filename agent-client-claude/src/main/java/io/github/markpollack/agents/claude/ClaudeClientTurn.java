/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.model.*;

/** One dispatch and immutable generation target, even after a subsequent turn starts. */
final class ClaudeClientTurn implements AgentExecutionProvider.Turn {

	private final ClaudeClientGeneration generation;

	private final String executionId;

	private final boolean retained;

	private final Consumer<String> identity;

	private final Consumer<Map<String, Object>> progress;

	private boolean started;

	private boolean stopped;

	private boolean finished;

	private volatile ClaudeExecutionResult result;

	/** What the read has accepted so far; the terminal result once one arrives. */
	private volatile ClaudeExecutionResult partial;

	/**
	 * Released when a dispatched call returns or throws, so its partial result is stable.
	 */
	private final java.util.concurrent.CountDownLatch readEnded = new java.util.concurrent.CountDownLatch(1);

	/**
	 * How long sealing waits for a stopped read to end. A stop kills the process, so the
	 * read normally ends at once; this stays well inside the recorder's 30 s stale bound.
	 */
	private static final long READ_END_WAIT_SECONDS = 10;

	private volatile String prompt;

	private volatile io.github.markpollack.agents.client.CaptureAcquisition acquisition = io.github.markpollack.agents.client.CaptureAcquisition.PENDING;

	ClaudeClientTurn(ClaudeClientGeneration generation, String executionId, boolean retained, Consumer<String> identity,
			Consumer<Map<String, Object>> progress) {
		this.generation = generation;
		this.executionId = executionId;
		this.retained = retained;
		this.identity = identity;
		this.progress = progress;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		startDispatch(request.goal());
		Instant start = Instant.now();
		try {
			if (this.generation.sources != null)
				this.generation.sources.observeProcess(this.generation.client);
			if (this.generation.retainedCapture != null)
				this.generation.retainedCapture.observeProcess(this.generation.client);
			this.generation.dispatched = true;
			if (this.generation.retainedCapture != null)
				this.generation.retainedCapture.begin(this.executionId);
			this.generation.client.query(request.goal());
			this.result = read();
			if (this.generation.retainedCapture != null)
				this.generation.retainedCapture.end(this.executionId, this.result.sessionId());
			if (!this.retained)
				this.generation.close();
			synchronized (this) {
				this.finished = true;
			}
			return response(start);
		}
		catch (RuntimeException failure) {
			try {
				this.generation.close();
			}
			catch (RuntimeException cleanup) {
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
		finally {
			this.readEnded.countDown();
		}
	}

	private AgentResponse response(Instant start) {
		var response = this.result.response(this.generation.cli.getModel(), Duration.between(start, Instant.now()),
				null, null);
		response.getMetadata().put("connectionId", this.generation.id);
		response.getMetadata().put("sessionReconnected", this.generation.reconnected);
		response.getMetadata().put("launchPath", "sdk-observed-process-inventory");
		return response;
	}

	private synchronized void startDispatch(String prompt) {
		if (this.stopped)
			throw new CancellationException("Turn stopped before dispatch");
		if (this.started)
			throw new IllegalStateException("Turn already dispatched");
		this.started = true;
		this.prompt = prompt;
	}

	private ClaudeExecutionResult read() {
		var result = new ClaudeExecutionResult();
		this.partial = result;
		var messages = this.generation.client.receiveResponse();
		while (messages.hasNext()) {
			var parsed = messages.next();
			result.accept(parsed);
			this.identity.accept(result.sessionId());
			if (parsed.isRegularMessage())
				this.progress.accept(Map.of("executionId", this.executionId, "connectionId", this.generation.id,
						"messageType", parsed.asMessage().getClass().getSimpleName()));
		}
		if (!result.terminalReceived())
			throw new IllegalStateException("Claude stream ended without a terminal result");
		return result;
	}

	@Override
	public io.github.markpollack.agents.client.CaptureAcquisition captureAcquisition() {
		if (this.generation.retainedCapture != null)
			return io.github.markpollack.agents.client.CaptureAcquisition.PENDING;
		return this.generation.sources == null ? io.github.markpollack.agents.client.CaptureAcquisition.UNAVAILABLE
				: this.acquisition;
	}

	/**
	 * The one-shot generation: the provider's sealed manifest and its digest at sealing,
	 * unchanged. A retained turn is not a one-shot generation.
	 */
	@Override
	public java.util.Optional<io.github.markpollack.agents.client.NativeGeneration> generation(
			io.github.markpollack.agents.client.ExecutionDecision decision) {
		if (this.generation.retainedCapture != null || this.generation.sources == null)
			return java.util.Optional.empty();
		var observed = observedForSealing();
		if (observed == null) {
			this.acquisition = io.github.markpollack.agents.client.CaptureAcquisition.INCOMPLETE;
			return java.util.Optional.empty();
		}
		try {
			var sealed = this.generation.sources.seal(this.generation, observed, this.executionId, this.prompt);
			this.acquisition = sealed.complete() ? io.github.markpollack.agents.client.CaptureAcquisition.COMPLETED
					: io.github.markpollack.agents.client.CaptureAcquisition.INCOMPLETE;
			return java.util.Optional.of(io.github.markpollack.agents.client.NativeGeneration.oneShot(
					"claude-code-" + this.generation.config.cliVersion() + "-sdk-stdio-session-file", decision,
					sealed.manifest(), sealed.sha256(), sealed.complete(),
					sealed.complete() ? "" : "Claude capture incomplete; see the manifest's capture_errors"));
		}
		catch (RuntimeException ex) {
			this.acquisition = io.github.markpollack.agents.client.CaptureAcquisition.FAILED;
			throw ex;
		}
	}

	@Override
	public Map<String, Object> recordingEvidence() {
		if (this.generation.retainedCapture != null)
			return Map.of("claudeRetainedCapture", this.generation.retainedCapture);
		if (this.generation.sources == null)
			return Map.of();
		var observed = observedForSealing();
		if (observed == null) {
			this.acquisition = io.github.markpollack.agents.client.CaptureAcquisition.INCOMPLETE;
			return Map.of();
		}
		try {
			var sealed = this.generation.sources.seal(this.generation, observed, this.executionId, this.prompt);
			this.acquisition = sealed.complete() ? io.github.markpollack.agents.client.CaptureAcquisition.COMPLETED
					: io.github.markpollack.agents.client.CaptureAcquisition.INCOMPLETE;
			return Map.of("claudeAcquisition", sealed);
		}
		catch (RuntimeException ex) {
			this.acquisition = io.github.markpollack.agents.client.CaptureAcquisition.FAILED;
			throw ex;
		}
	}

	/**
	 * The result to seal: the terminal one or, for a turn that was dispatched but
	 * stopped, timed out or failed before its terminal result, what the read had
	 * accepted. Sealing waits for that read to end so the partial result is no longer
	 * changing; the capture then records the missing terminal and cannot claim to be
	 * complete. A turn that was never dispatched has nothing to seal.
	 */
	private ClaudeExecutionResult observedForSealing() {
		var terminal = this.result;
		if (terminal != null)
			return terminal;
		synchronized (this) {
			if (!this.started)
				return null;
		}
		try {
			this.readEnded.await(READ_END_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		return this.result != null ? this.result : this.partial;
	}

	@Override
	public void stop(boolean mayInterruptIfRunning) {
		synchronized (this) {
			if (this.finished)
				return;
			this.stopped = true;
		}
		this.generation.close();
	}

}
