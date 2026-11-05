/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

/**
 * Presence check and diagnostic for the optional Journal 1.x legacy derived-trace
 * dependencies. This class references no Journal type, so it loads everywhere.
 */
final class LegacyTrace {

	static final String ARTIFACTS = "io.github.markpollack:journal-core:1.10.0 and io.github.markpollack:claude-code-capture:1.10.0";

	private static volatile Boolean available;

	private LegacyTrace() {
	}

	/**
	 * True when Journal 1.x's parser and trace types are loadable by this class's loader.
	 */
	static boolean available() {
		Boolean known = available;
		if (known == null) {
			known = loadable("io.github.markpollack.journal.claude.SessionLogParser")
					&& loadable("io.github.markpollack.journal.trace.TraceContentMode");
			available = known;
		}
		return known;
	}

	private static boolean loadable(String name) {
		try {
			Class.forName(name, false, LegacyTrace.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError ex) {
			return false;
		}
	}

	/** The actionable message for a legacy tracing request without its dependencies. */
	static String requirement(String feature) {
		return feature + " requires the legacy Journal 1.x derived-trace dependencies on the runtime classpath; add "
				+ ARTIFACTS
				+ " (optional dependencies of agent-claude since 0.32.1; see docs/legacy-tracing-migration.md)";
	}

	static void require(String feature) {
		if (!available()) {
			throw new IllegalStateException(requirement(feature));
		}
	}

}
