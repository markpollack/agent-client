/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.Objects;
import java.util.Optional;

/**
 * How a recorder treated an execution's native sources. It describes the archive only: it
 * never gates the execution result, and a judge verdict never depends on it.
 *
 * @param outcome one of the four distinct outcomes
 * @param acquisitionProfile the provider's acquisition profile, when it sealed sources
 * @param terminalProfile the profile the initial binding was delivered under
 * @param detail the qualifier's verdict, the unqualified profile, or what was not
 * captured
 */
public record NativeRecording(Outcome outcome, Optional<String> acquisitionProfile, String terminalProfile,
		String detail) {

	public NativeRecording {
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(acquisitionProfile, "acquisitionProfile");
		Objects.requireNonNull(terminalProfile, "terminalProfile");
		Objects.requireNonNull(detail, "detail");
	}

	/**
	 * Every outcome still delivers one initial binding, so the call is archived and
	 * linked.
	 */
	public enum Outcome {

		/**
		 * A qualifier accepted the sources; the binding carries them under its native
		 * profile.
		 */
		ATTACHED,

		/**
		 * No qualifier exists for the profile; an evidence-only binding names the
		 * profile.
		 */
		UNQUALIFIED,

		/**
		 * A known qualifier, or the digest check, refused; an evidence-only binding
		 * carries the verdict.
		 */
		REJECTED,

		/** The provider sealed no native sources; an evidence-only binding says so. */
		NOT_CAPTURED

	}

}
