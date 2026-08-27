/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The three trajectory addressing schemes measured across ACP CLIs on 2026-08-26, as
 * reusable {@link AcpTrajectoryLocator}s.
 *
 * <p>
 * They are collected here rather than hidden in each provider module for one reason: read
 * together they are the argument for the seam. Three agents, three schemes, no two of
 * which take the same inputs — and none of it derivable from the protocol, because ACP
 * has nothing to say about where a session's record lives.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public final class AcpTrajectoryLocators {

	private AcpTrajectoryLocators() {
	}

	/**
	 * {@code <root>/<sessionId>/<fileName>} — Junie's scheme.
	 *
	 * <p>
	 * Junie names each session directory with exactly the id it returns from
	 * {@code session/new}, verified against a live 26.8.24 run by snapshotting
	 * {@code ~/.junie/sessions} around the call. That correspondence is what makes this a
	 * lookup instead of a search, and it is worth re-checking whenever the session id
	 * format changes.
	 *
	 * <p>
	 * The file this addresses is secret-bearing: Junie writes the launching process's
	 * entire environment into it, unredacted. Resolve the path freely; do not copy the
	 * file.
	 * @param root the sessions directory
	 * @param fileName the trajectory file within a session directory
	 * @return a locator keyed on session id alone
	 */
	public static AcpTrajectoryLocator bySessionId(Path root, String fileName) {
		return ref -> (ref.sessionId() == null || ref.sessionId().isBlank()) ? null
				: root.resolve(ref.sessionId()).resolve(fileName);
	}

	/**
	 * {@code <root>/<url-encoded cwd>/<sessionId>/<fileName>} — Grok's scheme.
	 *
	 * <p>
	 * Grok partitions its sessions by working directory before session id, so the session
	 * id alone does not address a run. The directory name is the absolute working
	 * directory percent-encoded, including its separators — {@code /srv/project} becomes
	 * {@code %2Fsrv%2Fproject} — which is why this uses {@link URLEncoder} with every
	 * character escaped rather than a path resolve.
	 * @param root the sessions directory
	 * @param fileName the trajectory file within a session directory
	 * @return a locator keyed on working directory and session id
	 */
	public static AcpTrajectoryLocator byWorkingDirectoryAndSessionId(Path root, String fileName) {
		return ref -> {
			if (ref.sessionId() == null || ref.sessionId().isBlank() || ref.workingDirectory() == null) {
				return null;
			}
			String key = URLEncoder.encode(ref.workingDirectory().toAbsolutePath().toString(), StandardCharsets.UTF_8)
				.replace("+", "%20");
			return root.resolve(key).resolve(ref.sessionId()).resolve(fileName);
		};
	}

	/**
	 * {@code <root>/**}{@code /*<first 8 of sessionId>.jsonl} — Gemini CLI's scheme.
	 *
	 * <p>
	 * Gemini CLI names a chat file {@code session-<timestamp>-<first 8 of session
	 * id>.jsonl} under a per-project directory, so neither the full id nor a fixed path
	 * addresses it. This matches on the id prefix and, where several match, takes the
	 * most recently modified — the timestamp in the name is the CLI's own disambiguation.
	 *
	 * <p>
	 * Written from Gemini CLI 0.54.4's on-disk layout and covered by tests over a
	 * synthetic tree. It is <strong>not</strong> confirmed against a live ACP session:
	 * this account's {@code oauth-personal} tier is refused by the current CLI, which
	 * fails {@code session/new} while leaving {@code initialize} working. Treat the
	 * prefix length and the directory depth as unverified against a real run.
	 * @param root the directory holding per-project chat directories
	 * @param prefixLength how many leading characters of the session id appear in the
	 * file name
	 * @return a locator keyed on a session id prefix
	 */
	public static AcpTrajectoryLocator bySessionIdPrefix(Path root, int prefixLength) {
		return ref -> {
			if (ref.sessionId() == null || ref.sessionId().length() < prefixLength || !Files.isDirectory(root)) {
				return null;
			}
			String suffix = ref.sessionId().substring(0, prefixLength) + ".jsonl";
			try (Stream<Path> tree = Files.walk(root)) {
				return tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(suffix))
					.max(Comparator.comparingLong(path -> lastModified(path)))
					.orElse(null);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		}
		catch (IOException ex) {
			return Long.MIN_VALUE;
		}
	}

}
