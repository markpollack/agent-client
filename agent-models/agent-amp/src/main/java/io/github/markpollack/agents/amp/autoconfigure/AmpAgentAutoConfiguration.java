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

package io.github.markpollack.agents.amp.autoconfigure;

import io.github.markpollack.agents.amp.AmpAgentModel;
import io.github.markpollack.agents.amp.AmpAgentOptions;
import io.github.markpollack.agents.ampsdk.AmpClient;
import io.github.markpollack.agents.ampsdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Amp agent model.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(AmpAgentModel.class)
@EnableConfigurationProperties(AmpAgentProperties.class)
public class AmpAgentAutoConfiguration {

	/**
	 * Creates an Amp CLI client for interfacing with the Amp CLI.
	 * @param properties agent configuration properties
	 * @return configured Amp client
	 */
	@Bean
	@ConditionalOnMissingBean
	public AmpClient ampClient(AmpAgentProperties properties) {
		ExecuteOptions options = ExecuteOptions.builder()
			.dangerouslyAllowAll(properties.isDangerouslyAllowAll())
			.timeout(properties.getTimeout())
			.build();

		return AmpClient.create(options);
	}

	/**
	 * Creates an Amp agent model with automatic dependency injection.
	 * @param ampClient the Amp CLI client
	 * @param properties agent configuration properties
	 * @param sandboxProvider sandbox for secure command execution
	 * @return configured Amp agent model
	 */
	@Bean
	@ConditionalOnMissingBean
	public AgentModel agentModel(AmpClient ampClient, AmpAgentProperties properties,
			ObjectProvider<Sandbox> sandboxProvider) {

		AmpAgentOptions options = AmpAgentOptions.builder()
			.model(properties.getModel())
			.timeout(properties.getTimeout())
			.dangerouslyAllowAll(properties.isDangerouslyAllowAll())
			.executablePath(properties.getExecutablePath())
			.build();

		return new AmpAgentModel(ampClient, options, sandboxProvider.getIfAvailable());
	}

}
