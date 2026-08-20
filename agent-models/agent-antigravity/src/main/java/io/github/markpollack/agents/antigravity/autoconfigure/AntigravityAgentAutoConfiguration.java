/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.antigravity.autoconfigure;

import io.github.markpollack.agents.antigravity.AntigravityAgentModel;
import io.github.markpollack.agents.antigravity.AntigravityAgentOptions;
import io.github.markpollack.agents.antigravitysdk.AntigravityClient;
import io.github.markpollack.agents.antigravitysdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Antigravity agent.
 *
 * @author Mark Pollack
 * @since 0.27.0
 */
@AutoConfiguration
@ConditionalOnClass(AntigravityAgentModel.class)
@EnableConfigurationProperties(AntigravityAgentProperties.class)
public class AntigravityAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AntigravityClient antigravityClient(AntigravityAgentProperties properties) {
		ExecuteOptions options = ExecuteOptions.builder()
			.model(properties.getModel())
			.effort(properties.getEffort())
			.timeout(properties.getTimeout())
			.dangerouslySkipPermissions(properties.isDangerouslySkipPermissions())
			.mode(properties.getExecutionMode())
			.sandbox(properties.isSandbox())
			.executablePath(properties.getExecutablePath())
			.build();
		return AntigravityClient.create(options);
	}

	@Bean
	@ConditionalOnMissingBean
	public AgentModel agentModel(AntigravityClient antigravityClient, AntigravityAgentProperties properties) {
		AntigravityAgentOptions options = AntigravityAgentOptions.builder()
			.model(properties.getModel())
			.reasoningEffort(properties.getEffort())
			.timeout(properties.getTimeout())
			.dangerouslySkipPermissions(properties.isDangerouslySkipPermissions())
			.executionMode(properties.getExecutionMode())
			.executablePath(properties.getExecutablePath())
			.build();
		return new AntigravityAgentModel(antigravityClient, options);
	}

}
