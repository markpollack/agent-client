/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteResultTest {

	private static final String ACTIVITY = "session id: 019a0000-0000-7000-8000-000000000001\n";

	@Test
	void rolloutLinesAreTheRootLinesWhenChildrenArePresent() {
		CodexRollout root = new CodexRollout("019a0000-0000-7000-8000-000000000001", null, "/s/root.jsonl",
				List.of("root-meta", "root-event"));
		CodexRollout child = new CodexRollout("019a0000-0000-7000-8000-00000000000a",
				"019a0000-0000-7000-8000-000000000001", "/s/child.jsonl", List.of("child-meta", "child-event"));
		CodexRolloutHarvest harvest = new CodexRolloutHarvest(List.of(root, child), List.of("missing-thread"));

		ExecuteResult result = new ExecuteResult("out", ACTIVITY, 0, Duration.ZERO, "gpt-5", harvest);

		assertThat(result.getRolloutLines()).containsExactly("root-meta", "root-event");
		assertThat(result.getRollouts()).containsExactly(root, child);
		assertThat(result.getMissingRolloutThreadIds()).containsExactly("missing-thread");
		assertThat(result.getSessionId()).isEqualTo("019a0000-0000-7000-8000-000000000001");
	}

	@Test
	void flatLinesConstructorIsUnchangedAndExposesASingleRootRollout() {
		ExecuteResult result = new ExecuteResult("out", ACTIVITY, 0, Duration.ZERO, "gpt-5", List.of("a", "b"));

		assertThat(result.getRolloutLines()).containsExactly("a", "b");
		assertThat(result.getRollouts()).singleElement().satisfies(rollout -> {
			assertThat(rollout.threadId()).isEqualTo("019a0000-0000-7000-8000-000000000001");
			assertThat(rollout.parentThreadId()).isNull();
			assertThat(rollout.sourcePath()).isNull();
			assertThat(rollout.lines()).containsExactly("a", "b");
		});
		assertThat(result.getMissingRolloutThreadIds()).isEmpty();
	}

	@Test
	void absentRolloutsLeaveEveryAccessorEmpty() {
		ExecuteResult plain = new ExecuteResult("out", ACTIVITY, 0, Duration.ZERO, "gpt-5");
		ExecuteResult nullHarvest = new ExecuteResult("out", ACTIVITY, 0, Duration.ZERO, "gpt-5",
				(CodexRolloutHarvest) null);

		for (ExecuteResult result : List.of(plain, nullHarvest)) {
			assertThat(result.getRolloutLines()).isEmpty();
			assertThat(result.getRollouts()).isEmpty();
			assertThat(result.getMissingRolloutThreadIds()).isEmpty();
		}
	}

}
