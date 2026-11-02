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
 * Records accepted Client decisions using Journal's evidence-only receiver. No native
 * completeness or archive qualification is implied. Configure on AgentClient.Builder's
 * executionRecorder method; required execution/admission never waits for this recorder.
 */
public final class JournalExecutionRecorder implements ExecutionRecorder {

	private final TerminalReceiver receiver;

	private final Path sourceRoot;

	private final String namespace;

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
		String revision = decision.decisionId() + ":initial";
		byte[] frozen = DecisionEncoding.json(DecisionEncoding.object("codec", "journal-terminal-envelope/1",
				"producerNamespace", this.namespace, "executionId", decision.executionId(), "decisionId",
				decision.decisionId(), "decisionCodec", "client-decision/1", "decisionBytes",
				base64(DecisionEncoding.decision(decision)), "associationCodec", "journal-association/1",
				"associationBytes", base64(DecisionEncoding.association(decision, revision, null)), "initialRevisionId",
				revision, "receiverContract", TerminalReceiver.CONTRACT, "route", TerminalReceiver.ROUTE, "profile",
				TerminalReceiver.PROFILE))
			.getBytes(StandardCharsets.UTF_8);
		if (frozen.length > 1024 * 1024)
			throw new RecordingDeliveryException(FailureCode.RECORDING_FAILED,
					"Terminal payload exceeds receiver limit", null, false, false);
		return () -> deliver(frozen);
	}

	private String deliver(byte[] frozen) {
		try {
			String ack = this.receiver.receive(frozen, this.sourceRoot);
			return new ObjectMapper().readTree(ack).required("bindingId").asText();
		}
		catch (IllegalArgumentException ex) {
			throw new RecordingDeliveryException(FailureCode.RECORDING_CONFLICT, "Receiver rejected terminal payload",
					ex, false, false);
		}
		catch (IOException ex) {
			// The producer API does not distinguish integrity failures from reply loss.
			// Do
			// not automatically retry an unclassified storage failure; retain
			// uncertainty.
			throw new RecordingDeliveryException(FailureCode.PERSISTENCE_FAILED, "Receiver acceptance is unknown", ex,
					false, true);
		}
	}

	private static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

}
