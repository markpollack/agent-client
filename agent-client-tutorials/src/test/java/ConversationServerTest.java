
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tutorial 1 on the offline provider. */
@Timeout(30)
class ConversationServerTest {

	@TempDir
	Path workspaces;

	@Test
	void aLaterRequestContinuesTheConversationItNames() throws Exception {
		try (var server = new ConversationServer(new OfflineProvider())) {
			String first = server.start(this.workspaces);
			String second = server.start(this.workspaces);
			String opening = server.send(first, "What does this project build?").result().getResult();
			// A later HTTP request, by conversation id.
			String followUp = server.send(first, "Which module first?").result().getResult();
			String other = server.send(second, "Unrelated question").result().getResult();
			assertThat(opening).startsWith("turn 1 of ");
			assertThat(followUp).startsWith("turn 2 of " + opening.substring(10, 18));
			assertThat(other).startsWith("turn 1 of ").doesNotContain(opening.substring(10, 18));
		}
	}

	@Test
	void anUnknownOrEndedConversationIsAnErrorNotANewOne() throws Exception {
		try (var server = new ConversationServer(new OfflineProvider())) {
			assertThatThrownBy(() -> server.send("no-such-id", "hello")).isInstanceOf(NoSuchElementException.class);
			String id = server.start(this.workspaces);
			server.send(id, "hello").result();
			server.end(id);
			assertThatThrownBy(() -> server.send(id, "again")).isInstanceOf(NoSuchElementException.class);
		}
	}

}
