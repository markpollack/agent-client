/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.acp.AcpRunRecord;
import io.github.markpollack.agents.acp.AcpSessionRef;
import io.github.markpollack.agents.acp.AcpToolStep;
import io.github.markpollack.agents.acp.AcpUpdateFold;
import io.github.markpollack.agents.groksdk.types.PermissionMode;
import io.github.markpollack.journal.grok.GrokPhaseCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests for Grok's ACP profile, over the traffic of one real run.
 *
 * <p>
 * The fixtures are verbatim from a {@code grok agent stdio} run on 2026-08-26 — the
 * ninety-six {@code session/update} notifications and the {@code _meta} of the prompt
 * response — with paths rewritten. Asserting against the agent's own bytes is the only
 * way these tests can fail when Grok changes.
 */
class GrokAcpProfileTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final GrokAcpProfile profile = new GrokAcpProfile();

	@Test
	@DisplayName("Options are placed on the agent subcommand, before stdio")
	void optionsPrecedeTheStdioSubcommand(@TempDir Path workspace) {
		GrokAgentOptions options = GrokAgentOptions.builder()
			.model("grok-4.6")
			.reasoningEffort("high")
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		List<String> args = this.profile.launchArgs(workspace, options);

		// Position is not cosmetic: --always-approve is an option of `grok agent`, and
		// after `stdio` the CLI exits 2 before exchanging a byte of protocol.
		assertThat(args).containsSequence("agent", "--model", "grok-4.6");
		assertThat(args).containsSequence("--reasoning-effort", "high");
		assertThat(args).contains("--always-approve");
		assertThat(args.get(args.size() - 1)).isEqualTo("stdio");
	}

	@Test
	@DisplayName("Tool identity comes from the vendor _meta, not the display title")
	void toolNameComesFromVendorMeta() throws IOException {
		AcpUpdateFold fold = foldFixture();
		List<AcpToolStep> steps = fold.toolSteps();

		List<String> names = steps.stream()
			.map(step -> this.profile.toolName(step, fold.toolMeta(step.toolCallId())))
			.toList();

		assertThat(names).containsExactly("write", "read_file");
	}

	@Test
	@DisplayName("A tool step with no vendor meta falls back to its title")
	void toolNameFallsBackToTitle() {
		AcpToolStep step = new AcpToolStep("id", "ignored", "some_title", "read", "completed", List.of(), Map.of(),
				Map.of());

		assertThat(this.profile.toolName(step, Map.of())).isEqualTo("some_title");
		assertThat(this.profile.toolName(step, Map.of("x.ai/tool", Map.of("label", "Read")))).isEqualTo("some_title");
	}

	@Test
	@DisplayName("Cost ticks convert to the dollars the CLI itself reports")
	void ticksConvertToReportedDollars() throws IOException {
		Map<String, Object> fields = this.profile.providerFields(runRecord(null));

		// 57,660,600 ticks. The same task on the native plane reported 57,466,800 ticks
		// alongside total_cost_usd 0.00574668, which is what fixes the scale at 1e-10.
		assertThat((Double) fields.get("costUsd")).isEqualTo(0.00576606);
		assertThat(fields.get("costSource")).isEqualTo("reported");
		assertThat(fields.get("inputTokens")).isEqualTo(32763);
		assertThat(fields.get("numTurns")).isEqualTo(2);
	}

	@Test
	@DisplayName("The journal capture is reconstructible from the protocol alone")
	void captureIsBuiltWithoutParsingATrajectory() throws IOException {
		// Grok returns its whole usage vector on the prompt response, so the ACP plane
		// reproduces the capture the native plane parses out of stdout — no trajectory
		// file is read here. Junie cannot do this: it returns no usage over ACP and
		// reports cost only inside events.jsonl.
		GrokPhaseCapture capture = (GrokPhaseCapture) this.profile.capture(runRecord(null));

		assertThat(capture.model()).isEqualTo("grok-4.6");
		assertThat(capture.inputTokens()).isEqualTo(32763);
		assertThat(capture.outputTokens()).isEqualTo(236);
		assertThat(capture.thinkingTokens()).isEqualTo(61);
		assertThat(capture.totalCostUsd()).isEqualTo(0.00576606);
		assertThat(capture.isError()).isFalse();
		assertThat(capture.hasToolUses()).isTrue();
		assertThat(capture.toolUses()).extracting(t -> t.kind().wireValue()).containsExactly("edit", "read");
		assertThat(capture.textOutput()).contains("Contents confirmed");
		assertThat(capture.thinkingOutput()).isNotEmpty();
	}

	@Test
	@DisplayName("A run that did not reach end_turn is captured as an error")
	void nonEndTurnStopReasonIsAnError() throws IOException {
		AcpRunRecord aborted = new AcpRunRecord("s", "p", "", "", "max_turn_requests", List.of(), 0, 0, Map.of(), null,
				null, promptMeta(), null, Duration.ZERO);

		GrokPhaseCapture capture = (GrokPhaseCapture) this.profile.capture(aborted);

		assertThat(capture.isError()).isTrue();
		assertThat(capture.stopReason()).isEqualTo("max_turn_requests");
	}

	@Test
	@DisplayName("A missing usage block yields no invented cost")
	void absentUsageYieldsNoProviderFields() {
		AcpRunRecord bare = new AcpRunRecord("s", "p", "", "", "end_turn", List.of(), 0, 0, Map.of(), null, null,
				Map.of(), null, Duration.ZERO);

		assertThat(this.profile.providerFields(bare)).isEmpty();
		assertThat(((GrokPhaseCapture) this.profile.capture(bare)).totalCostUsd()).isZero();
	}

	@Test
	@DisplayName("The session directory is keyed by working directory, then session id")
	void trajectoryIsKeyedByWorkingDirectoryThenSessionId(@TempDir Path sessions, @TempDir Path workspace)
			throws IOException {
		String key = workspace.toAbsolutePath().toString().replace("/", "%2F");
		Path updates = sessions.resolve(key).resolve("01a040dc-daca").resolve("updates.jsonl");
		Files.createDirectories(updates.getParent());
		Files.writeString(updates, "{}\n");

		GrokAcpProfile scoped = new GrokAcpProfile(sessions);

		assertThat(scoped.trajectoryLocator().locate(new AcpSessionRef("01a040dc-daca", workspace, Instant.now())))
			.isEqualTo(updates);
	}

	private AcpRunRecord runRecord(Path trajectory) throws IOException {
		AcpUpdateFold fold = foldFixture();
		List<AcpToolStep> steps = fold.toolSteps()
			.stream()
			.map(step -> new AcpToolStep(step.toolCallId(),
					this.profile.toolName(step, fold.toolMeta(step.toolCallId())), step.title(), step.kind(),
					step.status(), step.locations(), step.rawInput(), step.rawOutput()))
			.toList();
		return new AcpRunRecord("01a040dc-daca-74e3-b4b4-78d955d7482e", "create hello.txt", fold.answer(),
				fold.thinking(), "end_turn", steps, fold.thoughtChunkCount(), fold.messageChunkCount(),
				fold.unknownUpdateKinds(), "grok", "1.0.5", promptMeta(), trajectory, Duration.ofSeconds(5));
	}

	private Map<String, Object> promptMeta() throws IOException {
		try (InputStream stream = getClass().getResourceAsStream("/grok-acp-prompt-meta.json")) {
			return MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() {
			});
		}
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
