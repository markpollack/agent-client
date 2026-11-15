/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude.autoconfigure;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClaudeAgentProperties#buildCLIOptions()}: each set property reaches the CLI
 * options, and blank or empty values are left out rather than passed as empty flags.
 */
class ClaudeCliOptionsFromPropertiesTest {

	@Test
	void everySetPropertyReachesTheCliOptions() {
		var properties = new ClaudeAgentProperties();
		properties.setPermissionMode("acceptEdits");
		properties.setMaxThinkingTokens(100);
		properties.setMaxTokens(200);
		properties.setSystemPrompt("be brief");
		properties.setAllowedTools(List.of("Read"));
		properties.setDisallowedTools(List.of("Bash"));
		properties.setJsonSchema(Map.of("type", "object"));
		properties.setMaxTurns(3);
		properties.setMaxBudgetUsd(1.5);
		properties.setFallbackModel("fallback");
		properties.setAppendSystemPrompt("more");
		properties.setAddDirs(List.of("/tmp"));
		properties.setSettings("settings.json");
		properties.setPermissionPromptToolName("ask");
		properties.setEffort("high");
		properties.setExtraArgs(Map.of("debug", "true"));
		properties.setEnv(Map.of("K", "V"));
		properties.setMaxBufferSize(1024);
		properties.setUser("agent");

		CLIOptions options = properties.buildCLIOptions();

		assertThat(options.getPermissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
		assertThat(options.getMaxThinkingTokens()).isEqualTo(100);
		assertThat(options.getMaxTokens()).isEqualTo(200);
		assertThat(options.getSystemPrompt()).isEqualTo("be brief");
		assertThat(options.getAllowedTools()).containsExactly("Read");
		assertThat(options.getDisallowedTools()).containsExactly("Bash");
		assertThat(options.getJsonSchema()).containsEntry("type", "object");
		assertThat(options.getMaxTurns()).isEqualTo(3);
		assertThat(options.getMaxBudgetUsd()).isEqualTo(1.5);
		assertThat(options.getFallbackModel()).isEqualTo("fallback");
		assertThat(options.getAppendSystemPrompt()).isEqualTo("more");
		assertThat(options.getAddDirs()).containsExactly(Path.of("/tmp"));
		assertThat(options.getSettings()).isEqualTo("settings.json");
		assertThat(options.getPermissionPromptToolName()).isEqualTo("ask");
		assertThat(options.getExtraArgs()).containsEntry("effort", "high").containsEntry("debug", "true");
		assertThat(options.getEnv()).containsEntry("K", "V");
		assertThat(options.getMaxBufferSize()).isEqualTo(1024);
		assertThat(options.getUser()).isEqualTo("agent");
	}

	@Test
	void blankAndEmptyValuesAreLeftOut() {
		var properties = new ClaudeAgentProperties();
		properties.setPermissionMode(" ");
		properties.setYolo(false);
		properties.setSystemPrompt(" ");
		properties.setAllowedTools(List.of());
		properties.setDisallowedTools(List.of());
		properties.setJsonSchema(Map.of());
		properties.setFallbackModel(" ");
		properties.setAppendSystemPrompt(" ");
		properties.setAddDirs(List.of());
		properties.setSettings(" ");
		properties.setPermissionPromptToolName(" ");
		properties.setEffort(" ");
		properties.setEnv(Map.of());
		properties.setUser(" ");

		CLIOptions options = properties.buildCLIOptions();

		assertThat(options.getSystemPrompt()).isNull();
		assertThat(options.getFallbackModel()).isNull();
		assertThat(options.getAppendSystemPrompt()).isNull();
		assertThat(options.getSettings()).isNull();
		assertThat(options.getPermissionPromptToolName()).isNull();
		assertThat(options.getUser()).isNull();
		assertThat(options.getExtraArgs()).doesNotContainKey("effort");
	}

	@Test
	void nothingSetKeepsTheDefaults() {
		var properties = new ClaudeAgentProperties();
		properties.setAllowedTools(null);
		properties.setDisallowedTools(null);
		properties.setAddDirs(null);

		CLIOptions options = properties.buildCLIOptions();

		assertThat(options.getPermissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
		assertThat(options.getMaxTurns()).isNull();
		assertThat(options.getMaxBudgetUsd()).isNull();
		assertThat(options.getMaxBufferSize()).isNull();
	}

}
