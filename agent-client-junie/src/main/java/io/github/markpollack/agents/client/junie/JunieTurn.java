/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import com.agentclientprotocol.sdk.client.CancellationSignal;
import io.github.markpollack.agents.acp.AcpAgentModel;
import io.github.markpollack.agents.client.AgentExecutionProvider;
import io.github.markpollack.agents.client.CaptureAcquisition;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * One prompt in one fresh Junie ACP session, through the synchronous
 * {@code AcpAgentModel}. A failed ACP run fails the turn; a run that ended with another
 * stop reason returns its response and seals that reason.
 */
final class JunieTurn implements AgentExecutionProvider.Turn {

	private final JunieConnection connection;

	private final String executionId;

	static final java.time.Duration TURN_WAIT = java.time.Duration.ofSeconds(30);

	private final CancellationSignal stop = new CancellationSignal();

	private final JunieObservation observation;

	private boolean started;

	private boolean stopped;

	private volatile List<String> argv = List.of();

	private volatile CaptureAcquisition acquisition = CaptureAcquisition.PENDING;

	private JunieAcquisition sealed;

	private final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

	JunieTurn(JunieConnection connection, String executionId) {
		this.connection = connection;
		this.executionId = executionId;
		this.observation = new JunieObservation(connection.configuration.sessionsDirectory());
	}

	JunieObservation observation() {
		return this.observation;
	}

	@Override
	public AgentResponse call(AgentTaskRequest request) {
		begin();
		Path directory = request.workingDirectory().toAbsolutePath().normalize();
		if (!directory.equals(this.connection.workingDirectory))
			throw new IllegalArgumentException("Junie request directory differs from connection options");
		var configuration = this.connection.configuration;
		var profile = new JunieLaunchProfile(configuration.sessionsDirectory(), System.getenv());
		var command = new ArrayList<String>();
		command.add(configuration.executable());
		command.addAll(profile.launchArgs(directory, request.options()));
		this.argv = JunieManifest.redact(command);
		var model = AcpAgentModel.builder(profile).command(configuration.executable()).captureEnabled(false).build();
		this.observation.before();
		AgentResponse response;
		try {
			response = model.call(request, this.stop, this.observation::sessionOpened);
		}
		finally {
			this.observation.ended();
			this.finished.countDown();
		}
		this.observation.response(response);
		Object error = response.getMetadata().getProviderFields().get("error");
		if (error != null)
			throw new IllegalStateException("Junie execution failed: " + error);
		return response;
	}

	private synchronized void begin() {
		if (this.started)
			throw new IllegalStateException("Junie turn cannot replay");
		if (this.stopped)
			throw new CancellationException("Junie turn stopped before dispatch");
		this.started = true;
	}

	@Override
	public CaptureAcquisition captureAcquisition() {
		return this.connection.configuration.acquisitionDirectory() == null ? CaptureAcquisition.UNAVAILABLE
				: this.acquisition;
	}

	/**
	 * Seal the native session for the accepted decision. Journal's qualifier, when the
	 * recorder has one, decides whether it qualifies; without it the recorder reports
	 * the generation {@code UNQUALIFIED}.
	 */
	@Override
	public Optional<NativeGeneration> generation(ExecutionDecision decision) {
		Path root = this.connection.configuration.acquisitionDirectory();
		if (root == null || !this.observation.dispatched())
			return Optional.empty();
		try {
			var acquisition = seal(root);
			Path manifest = acquisition.writeManifest(decision);
			this.acquisition = acquisition.complete() ? CaptureAcquisition.COMPLETED : CaptureAcquisition.INCOMPLETE;
			return Optional.of(NativeGeneration.oneShot(acquisition.profile(), decision, manifest,
					NativeGeneration.sha256(manifest), acquisition.complete(), acquisition.detail()));
		}
		catch (IOException ex) {
			this.acquisition = CaptureAcquisition.FAILED;
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			this.acquisition = CaptureAcquisition.FAILED;
			throw ex;
		}
	}

	/**
	 * A decision can be accepted before the turn returns (a stop), so sealing first waits,
	 * bounded, for the turn to end; a turn still running is sealed with that error.
	 */
	private synchronized JunieAcquisition seal(Path root) throws IOException {
		if (this.sealed == null)
			awaitTurn();
		if (this.sealed == null)
			this.sealed = JunieAcquisition.seal(root, this.executionId, this.connection.id,
					this.connection.workingDirectory, this.argv, this.observation);
		return this.sealed;
	}

	private void awaitTurn() {
		try {
			if (!this.finished.await(TURN_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
				this.observation.notEnded();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			this.observation.notEnded();
		}
	}

	@Override
	public void stop(boolean mayInterruptIfRunning) {
		synchronized (this) {
			this.stopped = true;
		}
		this.stop.cancel();
	}

}
