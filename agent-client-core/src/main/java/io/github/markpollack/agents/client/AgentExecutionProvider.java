/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * Opt-in transport seam for the bounded Client lifecycle. Native adapters must explicitly
 * implement this contract before using async or retained Client sessions. The caller
 * lends the provider; Client owns each opened connection.
 */
public interface AgentExecutionProvider extends AgentApi {

	/**
	 * Open without sending a task. Returning transfers a ready connection to Client.
	 * Throw only after cleaning partial acquisition resources; never orphan them.
	 */
	Connection open(AgentOptions options);

	default Connection open(AgentOptions options, boolean retained) {
		return open(options);
	}

	@Override
	default AgentResponse call(AgentTaskRequest request) {
		throw new UnsupportedOperationException("Use AgentClient to own the execution lifecycle");
	}

	default SessionCapabilities capabilities() {
		return new SessionCapabilities(new Capability(true, java.util.Optional.empty()),
				new Capability(false, java.util.Optional.of("Exact-history loading is not qualified")),
				new Capability(false, java.util.Optional.of("Native progress is not implemented")));
	}

	interface Connection extends AutoCloseable {

		default java.util.Optional<ProviderReference> providerReference() {
			return java.util.Optional.empty();
		}

		default java.util.Optional<String> connectionId() {
			return java.util.Optional.empty();
		}

		default Turn prepare(String executionId, java.util.function.Consumer<java.util.Map<String, Object>> progress) {
			return prepare(executionId);
		}

		/** Replace only after confirmed old-resource cessation; never replay a prompt. */
		default void refresh(AgentOptions options) {
			throw new UnsupportedOperationException("Binding refresh is not supported");
		}

		/** Create an exact turn target without dispatching provider work. */
		Turn prepare(String executionId);

		/**
		 * Recording diagnostics the provider established while closing: secondary
		 * evidence that was or was not written (for example a retained thread manifest),
		 * never an execution result. Read after {@link #close()}.
		 */
		default java.util.List<String> recordingDiagnostics() {
			return java.util.List.of();
		}

		/** Return only when connection resources have ceased. */
		@Override
		void close();

	}

	interface Turn {

		default CaptureAcquisition captureAcquisition() {
			return CaptureAcquisition.UNAVAILABLE;
		}

		/**
		 * Optional bounded source preparation, invoked only on Client's recording lane
		 * after acceptance.
		 */
		default java.util.Map<String, Object> recordingEvidence() {
			return java.util.Map.of();
		}

		/**
		 * Dispatch once. Return/throw only when required provider processing for this
		 * turn has ceased. An earlier stop must prevent subsequent dispatch. Never replay
		 * work.
		 */
		AgentResponse call(AgentTaskRequest request);

		/** Request cessation of this turn only, even if a successor has since started. */
		void stop(boolean mayInterruptIfRunning);

	}

}
