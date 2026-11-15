/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode.autoconfigure;

import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.qwencode.QwenCodeAgentModel;
import io.github.markpollack.agents.qwencode.QwenCodeAgentOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Qwen Code agent model.
 *
 * @author Spring AI Community
 * @since 0.12.0
 */
@AutoConfiguration
@ConditionalOnClass(QwenCodeAgentModel.class)
@EnableConfigurationProperties(QwenCodeAgentProperties.class)
public class QwenCodeAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	// AgentModel kept for compatibility until the AgentApi migration release
	@SuppressWarnings("removal")
	public AgentModel agentModel(QwenCodeAgentProperties properties) {
		QwenCodeAgentOptions options = QwenCodeAgentOptions.builder()
			.model(properties.getModel())
			.timeout(properties.getTimeout())
			.yolo(properties.isYolo())
			.executablePath(properties.getExecutablePath())
			.build();

		return new QwenCodeAgentModel(options);
	}

}
