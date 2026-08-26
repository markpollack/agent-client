/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie.autoconfigure;

import io.github.markpollack.agents.junie.JunieAgentModel;
import io.github.markpollack.agents.junie.JunieAgentOptions;
import io.github.markpollack.agents.model.AgentModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Junie agent.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
@AutoConfiguration
@ConditionalOnClass(JunieAgentModel.class)
@EnableConfigurationProperties(JunieAgentProperties.class)
public class JunieAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AgentModel agentModel(JunieAgentProperties properties) {
		JunieAgentOptions options = JunieAgentOptions.builder()
			.model(properties.getModel())
			.effort(properties.getEffort())
			.timeout(properties.getTimeout())
			.executablePath(properties.getExecutablePath())
			.extras(properties.getExtras())
			.build();

		return JunieAgentModel.builder()
			.command(properties.getExecutablePath())
			.defaultOptions(options)
			.sessionsDirectory(properties.getSessionsDirectory())
			.captureEnabled(properties.isCaptureEnabled())
			.build();
	}

}
