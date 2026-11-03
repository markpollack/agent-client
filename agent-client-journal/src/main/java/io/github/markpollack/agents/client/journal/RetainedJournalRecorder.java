/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.journal.ocsf.writer.TerminalReceiver;
import io.github.markpollack.journal.ocsf.writer.WriterOptions;

/**
 * Opt-in two-turn retained recording. Initial delivery freezes the retained profile and
 * original D/P. After session close and both initial ACKs, explicitly call attach for
 * each execution, off the result/admission path. No provider invocation or automatic
 * final delivery is scheduled. Use a fresh recorder for each two-turn session.
 */
public final class RetainedJournalRecorder implements ExecutionRecorder {

	private final JournalExecutionRecorder recorder;

	private final Path directory;

	private final Map<String, Entry> entries = new LinkedHashMap<>();

	RetainedJournalRecorder(Path directory, String namespace) throws IOException {
		this(new TerminalReceiver(directory, namespace, WriterOptions.defaults()), directory, namespace);
	}

	RetainedJournalRecorder(TerminalReceiver receiver, Path directory, String namespace) {
		this.recorder = new JournalExecutionRecorder(receiver, directory, namespace);
		this.directory = directory;
	}

	@Override
	public synchronized Supplier<String> prepare(ExecutionDecision decision) {
		var existing = this.entries.get(decision.executionId());
		if (existing != null) {
			if (!DecisionEncoding.decision(existing.decision).equals(DecisionEncoding.decision(decision)))
				throw new IllegalArgumentException("Original retained decision changed");
			return existing::deliverInitial;
		}
		if (this.entries.size() == 2)
			throw new IllegalStateException("Retained recorder is limited to two executions");
		var initial = this.recorder.preparePayload(decision, RetainedAssociation.initial(decision), this.directory,
				RetainedAssociation.pending(decision), TerminalReceiver.RETAINED_PROFILE);
		var entry = new Entry(decision, initial);
		this.entries.put(decision.executionId(), entry);
		return entry::deliverInitial;
	}

	/**
	 * Qualify and attach the sealed generation to this execution's original decision. A
	 * repeated explicit call resubmits the same prepared association after an ambiguous
	 * reply, or returns its remembered ACK. It never changes the original D/P.
	 */
	public String attach(String executionId, Path generationManifest) {
		return entry(executionId).attach(generationManifest.toAbsolutePath().normalize());
	}

	/**
	 * Current secondary attachment state, independently of AgentExecution.recording().
	 */
	public Attachment attachment(String executionId) {
		var entry = entry(executionId);
		return new Attachment(executionId, entry.decision.decisionId(), Optional.ofNullable(entry.initialBinding),
				entry.delivery);
	}

	private synchronized Entry entry(String executionId) {
		var entry = this.entries.get(executionId);
		if (entry == null)
			throw new IllegalArgumentException("No retained decision registered for execution");
		return entry;
	}

	/** Initial ACK custody and latest final attachment delivery observation. */
	public record Attachment(String executionId, String decisionId, Optional<String> initialBindingId,
			TerminalDelivery delivery) {
	}

	private final class Entry {

		private final ExecutionDecision decision;

		private final Supplier<String> initial;

		private volatile String initialBinding;

		private volatile TerminalDelivery delivery = new TerminalDelivery.Pending(Instant.now());

		private Supplier<String> prepared;

		private Path generation;

		private int attempts;

		Entry(ExecutionDecision decision, Supplier<String> initial) {
			this.decision = decision;
			this.initial = initial;
		}

		synchronized String deliverInitial() {
			if (this.initialBinding == null)
				this.initialBinding = this.initial.get();
			return this.initialBinding;
		}

		synchronized String attach(Path input) {
			validateAttachment(input);
			if (this.delivery instanceof TerminalDelivery.Completed complete)
				return complete.bindingId();
			this.delivery = new TerminalDelivery.Processing(this.decision.decisionId() + ":retained-final",
					++this.attempts, Instant.now());
			try {
				prepareAttachment(input);
				String binding = this.prepared.get();
				this.delivery = new TerminalDelivery.Completed(binding, Instant.now(), false);
				return binding;
			}
			catch (RecordingDeliveryException ex) {
				failed(ex.code(), ex, ex.acceptanceUnknown());
				throw ex;
			}
			catch (IOException | RuntimeException ex) {
				failed(FailureCode.RECORDING_FAILED, ex, false);
				throw new RecordingDeliveryException(FailureCode.RECORDING_FAILED,
						"Retained attachment preparation failed", ex, false, false);
			}
		}

		private void validateAttachment(Path input) {
			if (this.initialBinding == null)
				throw new IllegalStateException("Initial decision ACK is still pending");
			if (this.generation != null && !this.generation.equals(input))
				throw new IllegalArgumentException("A different generation cannot replace the frozen attachment");
		}

		private void prepareAttachment(Path input) throws IOException {
			if (this.prepared != null)
				return;
			var source = RetainedAssociation.prepare(
					RetainedJournalRecorder.this.directory.resolve("retained-" + this.decision.executionId()),
					this.decision, input);
			this.prepared = RetainedJournalRecorder.this.recorder.prepareRevision(this.initialBinding,
					source.json().getBytes(StandardCharsets.UTF_8), source.root());
			this.generation = input;
		}

		private void failed(FailureCode code, Throwable ex, boolean unknown) {
			this.delivery = new TerminalDelivery.Failed(
					new FailureInfo(code, ex.getClass().getName(), String.valueOf(ex.getMessage()), List.of()), false,
					unknown);
		}

	}

}
