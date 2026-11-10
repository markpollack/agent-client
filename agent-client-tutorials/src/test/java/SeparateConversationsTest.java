
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;

import io.github.markpollack.agents.client.AgentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Tutorial 3 on the offline provider. */
@Timeout(30)
class SeparateConversationsTest {

	@TempDir
	Path workspaces;

	@Test
	void cancellingOneConversationLeavesTheOtherRunning() throws Exception {
		var provider = new OfflineProvider();
		provider.hold();
		try (var client = AgentClient.builder(provider).build()) {
			var pair = SeparateConversations.open(client, this.workspaces);
			var alice = pair.aliceAndNancy().goal("[hold] Review service A").runAsync();
			var bob = pair.bobAndDoug().goal("[hold] Review service B").runAsync();
			// Both turns are running at the same time.
			Await.until(() -> provider.dispatched().size() == 2);
			var cancelled = SeparateConversations.cancel(alice);
			assertThat(cancelled.cleanupConfirmed()).isTrue();
			assertThat(alice.isCancelled()).isTrue();
			assertThat(bob.isDone()).isFalse();
			provider.release();
			assertThat(bob.result().getResult()).startsWith("turn 1 of ").endsWith("Review service B");
			// Alice and Nancy's conversation is still usable.
			assertThat(pair.aliceAndNancy().goal("Try again").runAsync().result().getResult()).startsWith("turn 1 of ");
			assertThat(this.workspaces.resolve("alice-and-nancy")).isDirectory();
			assertThat(this.workspaces.resolve("bob-and-doug")).isDirectory();
		}
	}

}
