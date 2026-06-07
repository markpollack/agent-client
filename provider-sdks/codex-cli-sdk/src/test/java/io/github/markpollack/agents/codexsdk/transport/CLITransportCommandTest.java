/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.agents.codexsdk.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI-flag validation tests for {@link CLITransport#buildCommand}: verifies that
 * {@link ExecuteOptions} fields land in the actual Codex CLI argument list. These tests
 * exist because SDK flag mappings silently drift as CLIs evolve — assert the command
 * line, not just the options object.
 */
class CLITransportCommandTest {

	private final CLITransport transport = new CLITransport(Path.of("/tmp"), "codex");

	@Test
	@DisplayName("reasoningEffort maps to -c model_reasoning_effort=\"<value>\" (quoted TOML string)")
	void reasoningEffortMapsToConfigOverride() {
		ExecuteOptions options = ExecuteOptions.builder().reasoningEffort("high").build();

		List<String> command = transport.buildCommand("test goal", options, null);

		int cIndex = command.indexOf("-c");
		assertThat(cIndex).as("-c config override flag should be present").isGreaterThanOrEqualTo(0);
		assertThat(command.get(cIndex + 1)).isEqualTo("model_reasoning_effort=\"high\"");
	}

	@Test
	@DisplayName("no reasoningEffort means no -c model_reasoning_effort override")
	void noReasoningEffortMeansNoOverride() {
		ExecuteOptions options = ExecuteOptions.builder().build();

		List<String> command = transport.buildCommand("test goal", options, null);

		assertThat(command).noneMatch(arg -> arg.startsWith("model_reasoning_effort"));
	}

	@Test
	@DisplayName("model maps to --model <value>")
	void modelMapsToFlag() {
		ExecuteOptions options = ExecuteOptions.builder().model("gpt-5.4-mini").build();

		List<String> command = transport.buildCommand("test goal", options, null);

		int modelIndex = command.indexOf("--model");
		assertThat(modelIndex).isGreaterThanOrEqualTo(0);
		assertThat(command.get(modelIndex + 1)).isEqualTo("gpt-5.4-mini");
	}

	@Test
	@DisplayName("prompt follows the -- separator as the final argument")
	void promptIsFinalArgumentAfterSeparator() {
		ExecuteOptions options = ExecuteOptions.builder().reasoningEffort("minimal").build();

		List<String> command = transport.buildCommand("complex, 'quoted' goal", options, null);

		assertThat(command.get(command.size() - 2)).isEqualTo("--");
		assertThat(command.get(command.size() - 1)).isEqualTo("complex, 'quoted' goal");
	}

}
