/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.codex;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CodexSourceTest {

	@TempDir
	Path root;

	private Path file(String name) {
		return root.resolve(name + "-native-id.jsonl");
	}

	private String valid() {
		return "{\"type\":\"session_meta\",\"payload\":{\"id\":\"native-id\",\"cwd\":\"" + root
				+ "\",\"cli_version\":\"0.166.0\",\"source\":\"exec\"}}\n{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_started\",\"turn_id\":\"turn-id\"}}\n";
	}

	@Test
	void refusesPreexistingAndAmbiguousIdentityInsteadOfUsingRecency() throws Exception {
		Files.writeString(file("old"), valid());
		var existing = new CodexNativeSource(root);
		assertThatThrownBy(() -> existing.read("native-id", root, "0.166.0")).hasMessageContaining("predates");
		Files.delete(file("old"));
		var fresh = new CodexNativeSource(root);
		Files.writeString(file("new"), valid());
		Files.writeString(file("newer"), valid());
		assertThatThrownBy(() -> fresh.read("native-id", root, "0.166.0")).hasMessageContaining("ambiguous");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.166.0", "native-id", "exec", "turn-id" })
	void refusesIncorrectMetadataOrMissingTurn(String replaced) throws Exception {
		var capture = new CodexNativeSource(root);
		String content = valid().replace(replaced, replaced.equals("turn-id") ? "" : "wrong");
		Files.writeString(file("new"), content);
		assertThatThrownBy(() -> capture.read("native-id", root, "0.166.0")).isInstanceOf(java.io.IOException.class);
	}

	@Test
	void rejectsUnsafeNativeIdentityAndOversizeSource() throws Exception {
		var capture = new CodexNativeSource(root);
		assertThatThrownBy(() -> capture.read(null, root, "0.166.0")).isInstanceOf(java.io.IOException.class);
		assertThatThrownBy(() -> capture.read("../escape", root, "0.166.0")).isInstanceOf(java.io.IOException.class);
		Files.write(file("new"), new byte[16 * 1024 * 1024 + 1]);
		assertThatThrownBy(() -> capture.read("native-id", root, "0.166.0")).hasMessageContaining("limit");
	}

	@Test
	void rawDecoderRejectsTruncationMalformedUtf8AndTerminalContradiction() {
		assertThatThrownBy(() -> CodexResult.parse(new byte[] { (byte) 0xff, 10 }))
			.isInstanceOf(java.io.IOException.class);
		assertThatThrownBy(() -> CodexResult.parse("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.hasMessageContaining("incomplete");
		String text = "{\"type\":\"thread.started\",\"thread_id\":\"N\"}\n{\"type\":\"turn.completed\"}\n{}\n";
		assertThatThrownBy(() -> CodexResult.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.hasMessageContaining("after terminal");
	}

}
