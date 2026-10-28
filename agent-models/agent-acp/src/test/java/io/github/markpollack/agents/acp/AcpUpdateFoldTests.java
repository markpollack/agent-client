/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold, exercised against a real ACP stream.
 *
 * <p>
 * The fixture is the verbatim {@code session/update} traffic from one Grok 1.0.5 run over
 * {@code grok agent stdio} on 2026-08-26 — a two-tool task, ninety-six notifications —
 * with the working directory rewritten to {@code /workspace}, the home directory to
 * {@code /home/user}, and the agent's slash-command list — which enumerated local files
 * outside the run — replaced by a two-entry synthetic one, since the fold ignores that
 * kind entirely. Hand-written update maps would have proved only that the fold folds
 * what it was written to fold; the whole point of these assertions is the ratio between
 * what arrived on the wire and what actually happened.
 *
 * @author Mark Pollack
 */
class AcpUpdateFoldTests {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void foldsNinetySixWireUpdatesIntoTwoToolSteps() throws IOException {
		AcpUpdateFold fold = foldFixture();

		// 2 tool_call + 4 tool_call_update on the wire, 2 tools actually run. Counting
		// updates would have overstated tool use threefold for this run alone.
		assertThat(fold.toolSteps()).hasSize(2);
		assertThat(fold.thoughtChunkCount()).isEqualTo(55);
		assertThat(fold.messageChunkCount()).isEqualTo(32);
	}

	@Test
	void keepsTheMachineTitleWhenALaterUpdateReplacesItWithProse() throws IOException {
		List<AcpToolStep> steps = foldFixture().toolSteps();

		// Grok sends the machine name first and a prose relabel second; Junie sends them
		// the other way round. Neither order may be relied on, so the first title is
		// retained and the structured `kind` is tracked separately rather than being
		// overwritten by whichever update happened to land last.
		assertThat(steps).extracting(AcpToolStep::title).containsExactly("write", "read_file");
		assertThat(steps).extracting(AcpToolStep::kind).containsExactly("edit", "read");
	}

	@Test
	void takesStatusFromTheToolsOwnLaterUpdate() throws IOException {
		List<AcpToolStep> steps = foldFixture().toolSteps();

		// Status arrives on a third update carrying no title and no kind. A fold that
		// stopped at the first update per id would report both tools as never finishing.
		assertThat(steps).allMatch(AcpToolStep::isCompleted);
		assertThat(steps).extracting(AcpToolStep::status).containsExactly("completed", "completed");
	}

	@Test
	void collectsLocationsAndRawInputAcrossUpdatesOfTheSameCall() throws IOException {
		List<AcpToolStep> steps = foldFixture().toolSteps();

		assertThat(steps.get(0).locations()).containsExactly("/workspace/hello.txt");
		assertThat(steps.get(0).rawInput()).containsEntry("file_path", "/workspace/hello.txt");
		assertThat(steps.get(1).rawOutput()).containsKey("type");
	}

	@Test
	void countsAnUpdateKindTheSdkCannotTypeInsteadOfFailingOnIt() throws IOException {
		AcpUpdateFold fold = foldFixture();

		// acp-core 0.16.1 types ten update kinds. Grok and Junie both emit
		// session_info_update, which is not one of them; routed through the SDK's typed
		// consumer it throws inside the notification pipeline for every ACP agent at
		// once. Here it is a counted fact on the run.
		assertThat(fold.unknownUpdateKinds()).containsExactly(Map.entry("session_info_update", 1));
	}

	@Test
	void exposesVendorMetaWithoutInterpretingIt() throws IOException {
		AcpUpdateFold fold = foldFixture();
		AcpToolStep step = fold.toolSteps().get(0);

		// The ACP schema says clients must not assume semantics for _meta, so the fold
		// carries it and only a per-agent profile reads it.
		Map<String, Object> meta = fold.toolMeta(step.toolCallId());
		assertThat(meta).containsKey("x.ai/tool");
	}

	@Test
	void concatenatesAnswerAndThinkingSeparately() throws IOException {
		AcpUpdateFold fold = foldFixture();

		assertThat(fold.answer()).startsWith("I'll create `hello.txt`").endsWith("Contents confirmed: `HELLO`.");
		assertThat(fold.thinking()).startsWith("The user wants me to create a file named hello.txt");
		assertThat(fold.answer()).doesNotContain("The user wants me to");
	}

	@Test
	void ignoresAnUpdateWithNoKind() {
		AcpUpdateFold fold = new AcpUpdateFold();

		fold.accept(Map.of("update", Map.of("noSuchField", "value")));
		fold.accept(null);

		assertThat(fold.toolSteps()).isEmpty();
		assertThat(fold.unknownUpdateKinds()).isEmpty();
	}

	@Test
	void keepsToolCallsWithNoCorrelationIdApart() {
		AcpUpdateFold fold = new AcpUpdateFold();

		fold.accept(Map.of("update", Map.of("sessionUpdate", "tool_call", "title", "first")));
		fold.accept(Map.of("update", Map.of("sessionUpdate", "tool_call", "title", "second")));

		// Merging them would silently halve the tool count for an agent that omits the
		// id; two anonymous steps at least report the right number.
		assertThat(fold.toolSteps()).extracting(AcpToolStep::title).containsExactly("first", "second");
	}

	private AcpUpdateFold foldFixture() throws IOException {
		AcpUpdateFold fold = new AcpUpdateFold();
		try (InputStream stream = getClass().getResourceAsStream("/grok-acp-session-updates.jsonl");
				BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					fold.accept(MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
					}));
				}
			}
		}
		return fold;
	}

}
