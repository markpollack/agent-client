
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;

import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.ProviderReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tutorial 4 on the offline provider, plus the real Claude provider's declared
 * capability.
 */
@Timeout(30)
class PickUpLaterTest {

	@TempDir
	Path workspace;

	@Test
	void afterADeadProcessTheNextTurnReconnectsToTheSameHistory() throws Exception {
		var provider = new OfflineProvider();
		try (var client = AgentClient.builder(provider).build()) {
			var conversation = client.sessions().create(spec -> spec.workingDirectory(this.workspace));
			var first = PickUpLater.nextTurn(conversation, "Remember the number 7");
			assertThat(first.reconnected()).isFalse();
			provider.killProcesses();
			var next = PickUpLater.nextTurn(conversation, "Which number?");
			assertThat(next.reconnected()).isTrue();
			assertThat(next.sessionId()).isEqualTo(first.sessionId());
			assertThat(provider.dispatched()).hasSize(2);
		}
	}

	@Test
	void afterACancelledTurnTheNextTurnReconnects() throws Exception {
		var provider = new OfflineProvider();
		try (var client = AgentClient.builder(provider).build()) {
			var conversation = client.sessions().create(spec -> spec.workingDirectory(this.workspace));
			PickUpLater.nextTurn(conversation, "First");
			provider.hold();
			var held = conversation.goal("[hold] Long task").runAsync();
			Await.until(() -> provider.dispatched().size() == 2);
			held.cancel(true);
			assertThat(held.awaitTermination(java.time.Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
			var next = PickUpLater.nextTurn(conversation, "Shorter task");
			assertThat(next.reconnected()).isTrue();
			assertThat(conversation.goal("And one more").runAsync().result().getResult()).startsWith("turn 3 of ");
		}
	}

	@Test
	void afterARestartLoadIsRefusedWhereTheProviderCannotLoad() throws Exception {
		var provider = new OfflineProvider();
		try (var client = AgentClient.builder(provider).build()) {
			var conversation = client.sessions().create(spec -> spec.workingDirectory(this.workspace));
			PickUpLater.nextTurn(conversation, "Hello");
			ProviderReference saved = PickUpLater.reference(conversation).orElseThrow();
			assertThatThrownBy(() -> PickUpLater.load(client, provider, saved, this.workspace))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining(saved.nativeId());
			// Calling the registry directly is refused the same way; nothing is replayed.
			assertThatThrownBy(() -> client.sessions().load(saved, spec -> spec.workingDirectory(this.workspace)))
				.isInstanceOf(UnsupportedOperationException.class);
			assertThat(provider.dispatched()).hasSize(1);
		}
	}

	@Test
	void claudeOnMainDoesNotLoadAfterARestartYet() {
		var load = ClaudeClientProvider.builder().workingDirectory(this.workspace).build().capabilities().load();
		assertThat(load.supported()).isFalse();
		assertThat(load.reason()).contains("Cross-handle/restart ownership exclusion is not qualified");
	}

}
