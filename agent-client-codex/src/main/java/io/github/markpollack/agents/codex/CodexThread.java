/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.util.Optional;

/**
 * Shared native thread state across generations: exact emitted identity, the last sealed
 * native prefix and the provider-specific quarantine after any incomplete turn.
 */
final class CodexThread {

	private String nativeId;

	private String quarantine;

	private CodexNativeSource.Prefix nativePrefix;

	synchronized void observeIdentity(String id) {
		if (this.nativeId == null)
			this.nativeId = id;
		else if (!this.nativeId.equals(id))
			throw new IllegalStateException("Native thread identity changed");
	}

	synchronized Optional<String> nativeId() {
		return Optional.ofNullable(this.nativeId);
	}

	synchronized void quarantine(String reason) {
		if (this.quarantine == null)
			this.quarantine = reason;
	}

	synchronized Optional<String> quarantined() {
		return Optional.ofNullable(this.quarantine);
	}

	private String manifestFailure;

	synchronized void manifestFailure(String reason) {
		this.manifestFailure = reason;
	}

	synchronized Optional<String> manifestFailure() {
		return Optional.ofNullable(this.manifestFailure);
	}

	synchronized CodexNativeSource.Prefix nativePrefix() {
		return this.nativePrefix;
	}

	synchronized void nativePrefix(CodexNativeSource.Prefix prefix) {
		this.nativePrefix = prefix;
	}

}
