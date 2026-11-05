/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FreshStartObserverTest {

	@TempDir
	Path root;

	@Test
	void projectDirectoryEncodingMatchesTheCli() {
		assertThat(FreshStartObserver.encode("/home/u/.claude/pro ject")).isEqualTo("-home-u--claude-pro-ject");
	}

	@Test
	void existingHistoryIsReportedNeverTaggedAbsentAndBuilderRefusesConflictingSettings() throws Exception {
		Path cwd = Files.createDirectories(root.resolve("work"));
		var observer = new FreshStartObserver(root.resolve("projects"), cwd, "S");
		Files.createDirectories(observer.expected().getParent());
		Files.writeString(observer.expected(), "{\"sessionId\":\"S\"}\n");
		observer.probe();
		observer.scan();
		assertThat(observer.before()).containsKeys("bytes", "sha256");
		observer.dispatch("E", 0, 0);
		observer.probe();
		byte[] stdout = "{\"type\":\"result\",\"session_id\":\"S\"}\n".getBytes(StandardCharsets.UTF_8);
		Path out = Files.createDirectories(root.resolve("out"));
		var values = observer.finish(out, stdout, "G");
		assertThat(observer.prefix()).containsKey("unavailable");
		var lines = Files.readAllLines(out.resolve("observations.jsonl"));
		assertThat(lines).hasSize(4);
		assertThat(lines.get(0)).contains("\"kind\":\"probe\"", "\"result\":\"EXISTS\"");
		assertThat(lines.get(1)).contains("\"kind\":\"scan\"", observer.expected().toString());
		assertThat(lines.get(2)).contains("\"kind\":\"dispatch\"");
		assertThat(lines.get(3)).contains("\"kind\":\"final\"", "\"candidates\":[\"" + observer.expected() + "\"]");
		@SuppressWarnings("unchecked")
		var identity = (Map<String, Object>) values.get("observedNativeIdentity");
		assertThat(identity).containsEntry("recordType", "result").containsEntry("reportedSessionId", "S");
		@SuppressWarnings("unchecked")
		var fresh = (Map<String, Object>) values.get("freshStart");
		assertThat(fresh).containsEntry("processIdentity", "G").containsEntry("cwd", cwd.toString());
		assertThatThrownBy(() -> observer.finish(out, stdout, "G")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> ClaudeClientProvider.builder()
			.acquisitionDirectory(root, "2.1.316")
			.retainedTranscript(root.resolve("t"))
			.freshTranscript(root)
			.build()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClaudeClientProvider.builder().freshTranscript(root).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void absentStartIsTaggedAndIdentityNeedsADispatch() throws Exception {
		var observer = new FreshStartObserver(root.resolve("projects"), root, "S");
		observer.probe();
		assertThat(observer.before()).isEqualTo(FreshStartObserver.ABSENT_BEFORE_FIRST_DISPATCH);
		assertThat(observer.prefix()).containsKey("unavailable");
		Files.createDirectories(observer.expected().getParent());
		Files.writeString(observer.expected(), "{}\n");
		Path out = Files.createDirectories(root.resolve("out"));
		var values = observer.finish(out, new byte[0], "G");
		assertThat(values.get("observedNativeIdentity")).isNull();
		assertThat(Files.readAllLines(out.resolve("observations.jsonl"))).hasSize(2);
	}

}
