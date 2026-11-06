/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.journal.ocsf.writer.TerminalReceiver;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import io.github.markpollack.journal.ocsf.writer.WriterOptions;

/**
 * Records accepted Client decisions using Journal's terminal receiver, through one
 * generic one-shot path for every provider. For an execution whose provider sealed a
 * {@link NativeGeneration}, it re-checks the manifest digest, selects Journal's qualifier
 * by the acquisition profile, qualifies, binds the qualified sources and delivers one
 * initial binding under the qualifier's terminal profile.
 *
 * <p>
 * Every execution is archived and linked by its decision and correlation. When no
 * qualifier exists, or a qualifier or the digest check refuses, the initial binding is
 * evidence-only and its {@code missing} entry carries the unqualified profile or the
 * verdict. {@link AgentExecution#recording()} reports which of the four
 * {@link NativeRecording.Outcome outcomes} applied. None of them gates the execution
 * result. Configure on AgentClient.Builder's executionRecorder method.
 */
public final class JournalExecutionRecorder implements ExecutionRecorder {

	private final TerminalReceiver receiver;

	private final Path sourceRoot;

	private final String namespace;

	/**
	 * The same recorder as the constructor, which now qualifies Claude one-shot sources
	 * through the generic path. Kept until its callers migrate.
	 * @param directory the archive directory
	 * @param namespace the producer namespace
	 * @return the recorder
	 * @throws IOException if the receiver cannot open the directory
	 */
	public static JournalExecutionRecorder claude(Path directory, String namespace) throws IOException {
		return new JournalExecutionRecorder(directory, namespace);
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
	public boolean takesNativeGeneration() {
		return true;
	}

	/** The evidence-only initial binding, as for an execution with no native sources. */
	@Override
	public Supplier<String> prepare(ExecutionDecision decision) {
		return prepareRecording(decision, Optional.empty()).delivery();
	}

	@Override
	public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
		String revision = decision.decisionId() + ":initial";
		if (generation.isEmpty())
			return new Prepared(
					preparePayload(decision, revision, this.sourceRoot,
							DecisionEncoding.association(decision, revision, null), TerminalReceiver.PROFILE),
					new NativeRecording(NativeRecording.Outcome.NOT_CAPTURED, Optional.empty(),
							TerminalReceiver.PROFILE, "The provider sealed no native generation"));
		var sealed = generation.get();
		String profile = sealed.acquisitionProfile();
		String refusal = integrity(sealed, decision);
		if (refusal != null)
			return evidenceOnly(decision, revision, NativeRecording.Outcome.REJECTED, profile, refusal);
		var qualifier = AcquisitionQualifier.forProfile(profile);
		if (qualifier.isEmpty())
			return evidenceOnly(decision, revision, NativeRecording.Outcome.UNQUALIFIED, profile,
					"No Journal qualifier for acquisition profile " + profile);
		if (!sealed.complete())
			return evidenceOnly(decision, revision, NativeRecording.Outcome.REJECTED, profile,
					"Native acquisition is incomplete" + (sealed.detail().isEmpty() ? "" : ": " + sealed.detail()));
		Path output = this.sourceRoot.resolve("native-" + decision.executionId());
		NativeAssociation association;
		try {
			association = NativeAssociation.qualified(output, decision, revision, null,
					qualifier.get().qualify(sealed.manifest(), output));
		}
		catch (IllegalArgumentException ex) {
			return evidenceOnly(decision, revision, NativeRecording.Outcome.REJECTED, profile,
					"Journal's " + profile + " qualifier refused: " + ex.getMessage());
		}
		catch (IOException ex) {
			return evidenceOnly(decision, revision, NativeRecording.Outcome.REJECTED, profile,
					"Qualification could not read or write its inputs: " + ex);
		}
		String terminal = qualifier.get().terminalProfile();
		return new Prepared(preparePayload(decision, revision, output, association.json(), terminal),
				new NativeRecording(NativeRecording.Outcome.ATTACHED, Optional.of(profile), terminal, ""));
	}

	/** Ownership and the digest at sealing; null when both hold. */
	private static String integrity(NativeGeneration sealed, ExecutionDecision decision) {
		var member = new NativeGeneration.Member(decision.executionId(), decision.decisionId());
		if (!sealed.members().equals(List.of(member))
				|| !decision.connectionId().equals(Optional.of(sealed.connectionId())))
			return "The generation does not belong to this one-shot decision";
		try {
			if (!NativeGeneration.sha256(sealed.manifest()).equals(sealed.manifestSha256()))
				return "Acquisition manifest changed after sealing";
		}
		catch (IOException ex) {
			return "Acquisition manifest is unreadable: " + ex;
		}
		return null;
	}

	private Prepared evidenceOnly(ExecutionDecision decision, String revision, NativeRecording.Outcome outcome,
			String profile, String detail) {
		String association = DecisionEncoding.association(decision, revision, null, outcome.name() + ": " + detail);
		return new Prepared(preparePayload(decision, revision, this.sourceRoot, association, TerminalReceiver.PROFILE),
				new NativeRecording(outcome, Optional.of(profile), TerminalReceiver.PROFILE, detail));
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
