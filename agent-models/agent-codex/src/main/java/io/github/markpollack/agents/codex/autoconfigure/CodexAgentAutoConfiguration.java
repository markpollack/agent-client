/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex.autoconfigure;

import io.github.markpollack.agents.codex.CodexAgentModel;
import io.github.markpollack.agents.codex.CodexAgentOptions;
import io.github.markpollack.agents.codexsdk.CodexClient;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Spring Boot auto-configuration for Codex agent model.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(CodexAgentModel.class)
@EnableConfigurationProperties(CodexAgentProperties.class)
public class CodexAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public CodexClient codexClient(CodexAgentProperties properties) {
		ExecuteOptions options = ExecuteOptions.builder()
			.model(properties.getModel())
			.reasoningEffort(properties.getReasoningEffort())
			.timeout(properties.getTimeout())
			.fullAuto(properties.isFullAuto())
			.skipGitCheck(properties.isSkipGitCheck())
			.dangerouslyBypassSandbox(properties.isDangerouslyBypassSandbox())
			.additionalDirectories(properties.getAdditionalDirectories().stream().map(Path::of).toList())
			.build();

		return CodexClient.create(options);
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentModel agentModel(CodexClient codexClient, CodexAgentProperties properties,
			ObjectProvider<Sandbox> sandboxProvider) {

		CodexAgentOptions options = CodexAgentOptions.builder()
			.model(properties.getModel())
			.reasoningEffort(properties.getReasoningEffort())
			.timeout(properties.getTimeout())
			.fullAuto(properties.isFullAuto())
			.skipGitCheck(properties.isSkipGitCheck())
			.dangerouslyBypassSandbox(properties.isDangerouslyBypassSandbox())
			.additionalDirectories(properties.getAdditionalDirectories().stream().map(Path::of).toList())
			.executablePath(properties.getExecutablePath())
			.build();

		return new CodexAgentModel(codexClient, options, sandboxProvider.getIfAvailable());
	}

}
