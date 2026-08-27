/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three trajectory addressing schemes, one test each.
 *
 * <p>
 * Read as a group these are the evidence for the seam: three ACP agents, three schemes,
 * and no two of them addressable from the same inputs. A locator interface taking only a
 * session id would have fitted Junie and excluded both of the others.
 *
 * @author Mark Pollack
 */
class AcpTrajectoryLocatorsTests {

	private static final Instant STARTED_AT = Instant.parse("2026-08-26T21:36:50Z");

	@Test
	void junieAddressesASessionByIdAlone(@TempDir Path root) throws IOException {
		Path events = root.resolve("019876-abcd").resolve("events.jsonl");
		Files.createDirectories(events.getParent());
		Files.writeString(events, "{}\n");

		AcpTrajectoryLocator locator = AcpTrajectoryLocators.bySessionId(root, "events.jsonl");

		assertThat(locator.locate(ref("019876-abcd", root))).isEqualTo(events);
	}

	@Test
	void grokAddressesASessionByWorkingDirectoryThenId(@TempDir Path root, @TempDir Path workspace) throws IOException {
		String key = workspace.toAbsolutePath().toString().replace("/", "%2F");
		Path updates = root.resolve(key).resolve("01a040dc-daca").resolve("updates.jsonl");
		Files.createDirectories(updates.getParent());
		Files.writeString(updates, "{}\n");

		AcpTrajectoryLocator locator = AcpTrajectoryLocators.byWorkingDirectoryAndSessionId(root, "updates.jsonl");

		// The session id alone does not address a Grok run: the same id under a different
		// working directory is a different path, and there is no path to be found.
		assertThat(locator.locate(new AcpSessionRef("01a040dc-daca", workspace, STARTED_AT))).isEqualTo(updates);
		assertThat(locator.locate(new AcpSessionRef("01a040dc-daca", root, STARTED_AT))).isNotEqualTo(updates);
	}

	@Test
	void geminiAddressesASessionByAnIdPrefix(@TempDir Path root) throws IOException {
		Path chats = root.resolve("myproject").resolve("chats");
		Files.createDirectories(chats);
		Path match = chats.resolve("session-2026-08-10T13-55-ba3c0750.jsonl");
		Files.writeString(match, "{}\n");
		Files.writeString(chats.resolve("session-2026-08-10T13-52-2b72d807.jsonl"), "{}\n");

		AcpTrajectoryLocator locator = AcpTrajectoryLocators.bySessionIdPrefix(root, 8);

		assertThat(locator.locate(ref("ba3c0750-1111-2222-3333-444444444444", root))).isEqualTo(match);
	}

	@Test
	void geminiPrefersTheMostRecentFileWhenAPrefixIsAmbiguous(@TempDir Path root) throws IOException {
		Path chats = root.resolve("myproject").resolve("chats");
		Files.createDirectories(chats);
		Path older = chats.resolve("session-2026-08-10T13-52-ba3c0750.jsonl");
		Path newer = chats.resolve("session-2026-08-10T13-55-ba3c0750.jsonl");
		Files.writeString(older, "{}\n");
		Files.writeString(newer, "{}\n");
		Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
		Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000L));

		AcpTrajectoryLocator locator = AcpTrajectoryLocators.bySessionIdPrefix(root, 8);

		// Eight hex characters are not a session identity. Gemini's own disambiguation is
		// the timestamp in the file name, so the most recent match is the run that just
		// finished.
		assertThat(locator.locate(ref("ba3c0750-1111-2222-3333-444444444444", root))).isEqualTo(newer);
	}

	@Test
	void existingOnlyRejectsAPlausiblePathThatIsNotThere(@TempDir Path root) {
		AcpTrajectoryLocator locator = AcpTrajectoryLocators.bySessionId(root, "events.jsonl");

		// A locator that returns a well-formed path to a missing file is what produces a
		// capture that is silently empty rather than loudly absent.
		assertThat(locator.locate(ref("never-ran", root))).isNotNull();
		assertThat(locator.existingOnly().locate(ref("never-ran", root))).isNull();
	}

	@Test
	void everySchemeDeclinesABlankSessionId(@TempDir Path root) {
		AcpSessionRef blank = new AcpSessionRef("", root, STARTED_AT);

		assertThat(AcpTrajectoryLocators.bySessionId(root, "events.jsonl").locate(blank)).isNull();
		assertThat(AcpTrajectoryLocators.byWorkingDirectoryAndSessionId(root, "updates.jsonl").locate(blank)).isNull();
		assertThat(AcpTrajectoryLocators.bySessionIdPrefix(root, 8).locate(blank)).isNull();
		assertThat(AcpTrajectoryLocator.none().locate(blank)).isNull();
	}

	private static AcpSessionRef ref(String sessionId, Path workingDirectory) {
		return new AcpSessionRef(sessionId, workingDirectory, STARTED_AT);
	}

}
