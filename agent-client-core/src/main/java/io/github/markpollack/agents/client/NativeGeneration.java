/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.util.List;

/**
 * The native sources a provider sealed for accepted executions, as one generation. A
 * one-shot execution is a generation with one member.
 *
 * <p>
 * Core never interprets it. {@link #acquisitionProfile()} and {@link #manifest()} are
 * opaque here: the provider writes the manifest bytes in its own module, and a recorder
 * selects a qualifier by the profile string. The manifest is never rewritten after
 * sealing, and {@link #manifestSha256()} is its digest at sealing, which the recorder
 * re-checks before qualification.
 */
public interface NativeGeneration {

	/** Opaque to core; selects the recorder's qualifier. */
	String acquisitionProfile();

	/** The Client session that owns the members. */
	String sessionId();

	/** The Client connection that produced the generation. */
	String connectionId();

	/** Accepted executions in turn order; never empty. */
	List<Member> members();

	/** Sealed by the provider; never rewritten afterwards. */
	Path manifest();

	/** Lower-case hexadecimal SHA-256 of the manifest bytes at sealing. */
	String manifestSha256();

	/** Whether the provider observed every source it declares. */
	boolean complete();

	/** What the provider observed, for a reader; empty when there is nothing to add. */
	String detail();

	/**
	 * A one-shot generation: the accepted decision is its single member.
	 * @param acquisitionProfile the profile the provider wrote into the manifest
	 * @param decision the accepted decision that owns the sources
	 * @param manifest the sealed manifest
	 * @param manifestSha256 the manifest's digest at sealing
	 * @param complete whether every declared source was observed
	 * @param detail what the provider observed; empty when nothing to add
	 * @return the generation
	 */
	static NativeGeneration oneShot(String acquisitionProfile, ExecutionDecision decision, Path manifest,
			String manifestSha256, boolean complete, String detail) {
		return new Sealed(acquisitionProfile, decision.sessionId(), decision.connectionId().orElseThrow(),
				List.of(new Member(decision.executionId(), decision.decisionId())), manifest, manifestSha256, complete,
				detail);
	}

	/**
	 * The digest providers record at sealing.
	 * @param file the sealed manifest
	 * @return lower-case hexadecimal SHA-256 of its bytes
	 * @throws java.io.IOException if the file cannot be read
	 */
	static String sha256(Path file) throws java.io.IOException {
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(java.nio.file.Files.readAllBytes(file)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** A sealed generation as a value. */
	record Sealed(String acquisitionProfile, String sessionId, String connectionId, List<Member> members, Path manifest,
			String manifestSha256, boolean complete, String detail) implements NativeGeneration {

		public Sealed {
			java.util.Objects.requireNonNull(acquisitionProfile, "acquisitionProfile");
			java.util.Objects.requireNonNull(sessionId, "sessionId");
			java.util.Objects.requireNonNull(connectionId, "connectionId");
			members = List.copyOf(members);
			if (members.isEmpty())
				throw new IllegalArgumentException("A generation has at least one member");
			java.util.Objects.requireNonNull(manifest, "manifest");
			java.util.Objects.requireNonNull(manifestSha256, "manifestSha256");
			java.util.Objects.requireNonNull(detail, "detail");
		}

	}

	/** One accepted execution of the generation. */
	record Member(String executionId, String decisionId) {
	}

}
