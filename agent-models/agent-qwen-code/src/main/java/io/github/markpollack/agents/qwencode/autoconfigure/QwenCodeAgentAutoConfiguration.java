/*
 * Copyright 2024 Spring AI Community
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
