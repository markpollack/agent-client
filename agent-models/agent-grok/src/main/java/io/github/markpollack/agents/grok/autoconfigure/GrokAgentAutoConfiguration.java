/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok.autoconfigure;

import io.github.markpollack.agents.grok.GrokAgentModel;
import io.github.markpollack.agents.grok.GrokAgentOptions;
import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Grok agent.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
@AutoConfiguration
@ConditionalOnClass(GrokAgentModel.class)
@EnableConfigurationProperties(GrokAgentProperties.class)
public class GrokAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public GrokClient grokClient(GrokAgentProperties properties) {
		ExecuteOptions options = ExecuteOptions.builder()
			.model(properties.getModel())
			.reasoningEffort(properties.getReasoningEffort())
			.timeout(properties.getTimeout())
			.permissionMode(properties.getPermissionMode())
			.maxTurns(properties.getMaxTurns())
			.disableWebSearch(properties.isDisableWebSearch())
			.executablePath(properties.getExecutablePath())
			.build();
		return GrokClient.create(options);
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentModel agentModel(GrokClient grokClient, GrokAgentProperties properties) {
		GrokAgentOptions options = GrokAgentOptions.builder()
			.model(properties.getModel())
			.reasoningEffort(properties.getReasoningEffort())
			.timeout(properties.getTimeout())
			.permissionMode(properties.getPermissionMode())
			.maxTurns(properties.getMaxTurns())
			.disableWebSearch(properties.isDisableWebSearch())
			.executablePath(properties.getExecutablePath())
			.build();
		return new GrokAgentModel(grokClient, options);
	}

}
