/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.amazonq.autoconfigure;

import io.github.markpollack.agents.amazonq.AmazonQAgentModel;
import io.github.markpollack.agents.amazonq.AmazonQAgentOptions;
import io.github.markpollack.agents.amazonqsdk.AmazonQClient;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * Auto-configuration for Amazon Q Agent Model.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@Configuration
@ConditionalOnClass(AmazonQClient.class)
@EnableConfigurationProperties(AmazonQAgentProperties.class)
public class AmazonQAgentAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AmazonQClient amazonQClient(AmazonQAgentProperties properties) {
		if (properties.getExecutablePath() != null) {
			return AmazonQClient.create(Paths.get(System.getProperty("user.dir")), properties.getExecutablePath());
		}
		return AmazonQClient.create();
	}

	@Bean
	@ConditionalOnMissingBean
	public AmazonQAgentOptions amazonQAgentOptions(AmazonQAgentProperties properties) {
		return AmazonQAgentOptions.builder()
			.model(properties.getModel())
			.timeout(properties.getTimeout())
			.trustAllTools(properties.isTrustAllTools())
			.trustTools(properties.getTrustTools())
			.agent(properties.getAgent())
			.verbose(properties.isVerbose())
			.executablePath(properties.getExecutablePath())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public AmazonQAgentModel amazonQAgentModel(AmazonQClient amazonQClient, AmazonQAgentOptions amazonQAgentOptions,
			Sandbox sandbox) {
		return new AmazonQAgentModel(amazonQClient, amazonQAgentOptions, sandbox);
	}

}
