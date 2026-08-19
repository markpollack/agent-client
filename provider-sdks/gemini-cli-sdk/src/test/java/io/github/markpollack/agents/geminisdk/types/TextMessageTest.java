/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.geminisdk.types;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TextMessageTest {

	@Test
	void testUserMessage() {
		TextMessage message = TextMessage.user("Hello");

		assertThat(message.getType()).isEqualTo(MessageType.USER);
		assertThat(message.getContent()).isEqualTo("Hello");
		assertThat(message.isEmpty()).isFalse();
	}

	@Test
	void testAssistantMessage() {
		TextMessage message = TextMessage.assistant("Hi there!");

		assertThat(message.getType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(message.getContent()).isEqualTo("Hi there!");
		assertThat(message.isEmpty()).isFalse();
	}

	@Test
	void testSystemMessage() {
		TextMessage message = TextMessage.system("System info");

		assertThat(message.getType()).isEqualTo(MessageType.SYSTEM);
		assertThat(message.getContent()).isEqualTo("System info");
	}

	@Test
	void testErrorMessage() {
		TextMessage message = TextMessage.error("Error occurred");

		assertThat(message.getType()).isEqualTo(MessageType.ERROR);
		assertThat(message.getContent()).isEqualTo("Error occurred");
	}

	@Test
	void testEmptyMessage() {
		TextMessage message = TextMessage.assistant("");

		assertThat(message.isEmpty()).isTrue();

		TextMessage nullMessage = TextMessage.assistant(null);
		assertThat(nullMessage.isEmpty()).isTrue();

		TextMessage whitespaceMessage = TextMessage.assistant("   ");
		assertThat(whitespaceMessage.isEmpty()).isTrue();
	}

	@Test
	void testDefaultValues() {
		TextMessage message = new TextMessage(null, null);

		assertThat(message.getType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(message.getContent()).isEqualTo("");
	}

}