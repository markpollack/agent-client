
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentExecution;
import io.github.markpollack.agents.client.AgentSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Tutorial 2 on the offline provider: Bob and Doug share one conversation. */
@Timeout(30)
class ConversationInboxTest {

	@TempDir
	Path workspace;

	private final OfflineProvider provider = new OfflineProvider();

	private final Map<String, AgentExecution> turns = new ConcurrentHashMap<>();

	private AgentClient client;

	private AgentSession shared;

	private ConversationInbox inbox;

	@BeforeEach
	void open() {
		this.client = AgentClient.builder(this.provider).build();
		this.shared = this.client.sessions().create(spec -> spec.workingDirectory(this.workspace));
		this.inbox = new ConversationInbox(this.shared, 1, (input, turn) -> this.turns.put(input.user(), turn));
		this.provider.hold();
	}

	@AfterEach
	void close() throws Exception {
		this.provider.release();
		this.inbox.close();
		this.client.close();
	}

	@Test
	void anInputThatArrivesMidTurnWaitsItsTurn() throws Exception {
		assertThat(this.inbox.submit(new ConversationInbox.Input("Bob", "[hold] Plan the change")))
			.isEqualTo(ConversationInbox.Admission.QUEUED);
		Await.until(() -> this.provider.dispatched().size() == 1);
		// Doug's input arrives while Bob's turn runs.
		assertThat(this.inbox.submit(new ConversationInbox.Input("Doug", "Add a test for it")))
			.isEqualTo(ConversationInbox.Admission.QUEUED);
		Thread.sleep(50);
		assertThat(this.provider.dispatched()).containsExactly("From Bob: [hold] Plan the change");
		this.provider.release();
		Await.until(() -> this.turns.containsKey("Doug"));
		assertThat(this.turns.get("Doug").result().getResult()).startsWith("turn 2 of ");
		assertThat(this.provider.dispatched()).containsExactly("From Bob: [hold] Plan the change",
				"From Doug: Add a test for it");
	}

	@Test
	void anOverlappingSubmitIsRefusedBusyAndDispatchesNothing() throws Exception {
		this.inbox.submit(new ConversationInbox.Input("Bob", "[hold] Plan the change"));
		Await.until(() -> this.provider.dispatched().size() == 1);
		assertThat(ConversationInbox.submitDirectly(this.shared, new ConversationInbox.Input("Doug", "Me too")))
			.isEqualTo(409);
		assertThat(this.provider.dispatched()).hasSize(1);
	}

	@Test
	void theQueueAdvancesOnTerminationNotOnACancelledResult() throws Exception {
		this.inbox.submit(new ConversationInbox.Input("Bob", "[hold] Plan the change"));
		Await.until(() -> this.turns.containsKey("Bob") && this.provider.dispatched().size() == 1);
		this.inbox.submit(new ConversationInbox.Input("Doug", "Add a test for it"));
		AgentExecution bob = this.turns.get("Bob");
		assertThat(bob.cancel(true)).isTrue();
		// The cancelled result is done at once, but the turn is still stopping: the
		// session
		// would refuse a next input, so the inbox must not advance yet.
		assertThat(bob.isDone()).isTrue();
		assertThat(ConversationInbox.submitDirectly(this.shared, new ConversationInbox.Input("Nobody", "now")))
			.isEqualTo(409);
		assertThat(this.provider.dispatched()).hasSize(1);
		Await.until(() -> this.turns.containsKey("Doug"));
		assertThat(bob.termination().toCompletableFuture().isDone()).isTrue();
		var doug = this.turns.get("Doug");
		assertThat(doug.result().getResult()).startsWith("turn 1 of "); // Bob's cancelled
																		// turn added
																		// nothing
		assertThat(doug.awaitTermination(java.time.Duration.ofSeconds(10)).reconnected()).isTrue();
	}

	@Test
	void aFullInboxSaysSo() throws Exception {
		this.inbox.submit(new ConversationInbox.Input("Bob", "[hold] Plan the change"));
		Await.until(() -> this.provider.dispatched().size() == 1);
		assertThat(this.inbox.submit(new ConversationInbox.Input("Doug", "Second")))
			.isEqualTo(ConversationInbox.Admission.QUEUED);
		assertThat(this.inbox.submit(new ConversationInbox.Input("Doug", "Third")))
			.isEqualTo(ConversationInbox.Admission.FULL);
	}

}
