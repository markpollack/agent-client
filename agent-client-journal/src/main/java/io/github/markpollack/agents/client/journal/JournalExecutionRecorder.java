/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.journal.ocsf.writer.TerminalReceiver;
import io.github.markpollack.journal.ocsf.writer.WriterOptions;

/**
 * Records accepted Client decisions using Journal's terminal receiver. The default is
 * evidence-only; {@link #claude(Path, String)} qualifies complete one-shot Claude
 * sources. Configure on AgentClient.Builder's executionRecorder method; required
 * execution/admission never waits for this recorder.
 */
public final class JournalExecutionRecorder implements ExecutionRecorder {

	private final TerminalReceiver receiver;

	private final Path sourceRoot;

	private final String namespace;

	private boolean nativeClaude;

	/**
	 * Native one-shot acquisition when supplied; retained turns keep evidence-only
	 * semantics.
	 */
	public static JournalExecutionRecorder claude(Path directory, String namespace) throws IOException {
		var recorder = new JournalExecutionRecorder(directory, namespace);
		recorder.nativeClaude = true;
		return recorder;
	}

	/**
	 * Explicit two-turn retained profile; final attachments are requested after close.
	 */
	public static RetainedJournalRecorder retainedClaude(Path directory, String namespace) throws IOException {
		return new RetainedJournalRecorder(directory, namespace);
	}

	public JournalExecutionRecorder(Path directory, String namespace) throws IOException {
		this(new TerminalReceiver(directory, namespace, WriterOptions.defaults()), directory, namespace);
	}

	JournalExecutionRecorder(TerminalReceiver receiver, Path sourceRoot, String namespace) {
		this.receiver = receiver;
		this.sourceRoot = sourceRoot;
		this.namespace = namespace;
	}

	@Override
	public Supplier<String> prepare(ExecutionDecision decision) {
		return prepare(decision, java.util.Map.of());
	}

	@Override
	public Supplier<String> prepare(ExecutionDecision decision, java.util.Map<String, Object> evidence) {
		String revision = decision.decisionId() + ":initial";
		Path root = this.sourceRoot;
		String association = DecisionEncoding.association(decision, revision, null);
		String profile = TerminalReceiver.PROFILE;
		if (this.nativeClaude && evidence
			.get("claudeAcquisition") instanceof io.github.markpollack.agents.claude.ClaudeAcquisition source) {
			try {
				var nativeValue = NativeAssociation.prepare(this.sourceRoot.resolve("native-" + decision.executionId()),
						decision, revision, source);
				root = nativeValue.root();
				association = nativeValue.json();
				profile = TerminalReceiver.CLAUDE_PROFILE;
			}
			catch (IOException | IllegalArgumentException ex) {
				throw new RecordingDeliveryException(FailureCode.RECORDING_FAILED, "Native source qualification failed",
						ex, false, false);
			}
		}
		return preparePayload(decision, revision, root, association, profile);
	}

	Supplier<String> preparePayload(ExecutionDecision decision, String revision, Path root, String association,
			String profile) {
		byte[] frozen = DecisionEncoding
			.json(DecisionEncoding.object("codec", "journal-terminal-envelope/1", "producerNamespace", this.namespace,
					"executionId", decision.executionId(), "decisionId", decision.decisionId(), "decisionCodec",
					"client-decision/1", "decisionBytes", base64(DecisionEncoding.decision(decision)),
					"associationCodec", "journal-association/1", "associationBytes", base64(association),
					"initialRevisionId", revision, "receiverContract", TerminalReceiver.CONTRACT, "route",
					TerminalReceiver.ROUTE, "profile", profile))
			.getBytes(StandardCharsets.UTF_8);
		if (frozen.length > 1024 * 1024)
			throw new RecordingDeliveryException(FailureCode.RECORDING_FAILED,
					"Terminal payload exceeds receiver limit", null, false, false);
		return () -> deliver(frozen, root);
	}

	Supplier<String> prepareRevision(String binding, byte[] association, Path root) {
		byte[] frozen = association.clone();
		return () -> acknowledge(() -> this.receiver.receiveRevision(binding, frozen, root));
	}

	private String deliver(byte[] frozen, Path root) {
		return acknowledge(() -> this.receiver.receive(frozen, root));
	}

	private String acknowledge(ReceiptCall call) {
		try {
			String ack = call.receive();
			return new ObjectMapper().readTree(ack).required("bindingId").asText();
		}
		catch (IllegalArgumentException ex) {
			throw new RecordingDeliveryException(FailureCode.RECORDING_CONFLICT, "Receiver rejected terminal payload",
					ex, false, false);
		}
		catch (io.github.markpollack.journal.ocsf.writer.TerminalIntegrityException ex) {
			throw new RecordingDeliveryException(FailureCode.RECORDING_FAILED, "Receiver integrity check failed", ex,
					false, true);
		}
		catch (IOException ex) {
			// Other I/O remains ambiguous. Never infer a safely retryable failure.
			// Retain uncertainty; no automatic retry without a positive transient
			// classification.
			throw new RecordingDeliveryException(FailureCode.PERSISTENCE_FAILED, "Receiver acceptance is unknown", ex,
					false, true);
		}
	}

	@FunctionalInterface
	private interface ReceiptCall {

		String receive() throws IOException;

	}

	private static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

}
