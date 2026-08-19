/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.gemini.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.markpollack.agents.gemini.GeminiAgentModel;
import io.github.markpollack.agents.gemini.GeminiAgentOptions;
import io.github.markpollack.agents.geminisdk.GeminiClient;
import io.github.markpollack.agents.geminisdk.transport.CLIOptions;
import io.github.markpollack.sandbox.Sandbox;

/**
 * Auto-configuration for Gemini agent model support.
 *
 * <p>
 * This auto-configuration is active when the Gemini SDK is on the classpath and provides:
 * </p>
 * <ul>
 * <li>Automatic configuration of {@link GeminiClient}</li>
 * <li>Configuration of {@link GeminiAgentModel} with dependency injection</li>
 * <li>Integration with Spring Boot configuration properties</li>
 * <li>Sandbox integration for secure execution</li>
 * </ul>
 *
 * <p>
 * Configuration is driven by {@link GeminiAgentProperties} in the
 * {@code spring.ai.agents.gemini} namespace.
 * </p>
 *
 * @author Spring AI Community
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({ GeminiAgentModel.class, GeminiClient.class })
@EnableConfigurationProperties(GeminiAgentProperties.class)
public class GeminiAgentAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(GeminiAgentAutoConfiguration.class);

	/**
	 * Creates a GeminiClient bean if none exists.
	 * @param properties the Gemini agent properties
	 * @return configured GeminiClient
	 */
	@Bean
	@ConditionalOnMissingBean
	public GeminiClient geminiClient(GeminiAgentProperties properties) {
		logger.debug("Creating GeminiClient with properties: model={}, timeout={}", properties.getModel(),
				properties.getTimeout());

		CLIOptions cliOptions = properties.buildCLIOptions();

		// Set executable path if provided
		if (properties.getExecutablePath() != null) {
			System.setProperty("gemini.cli.path", properties.getExecutablePath());
		}

		return GeminiClient.create(cliOptions);
	}

	/**
	 * Creates a GeminiAgentOptions bean if none exists.
	 * @param properties the Gemini agent properties
	 * @return configured GeminiAgentOptions
	 */
	@Bean
	@ConditionalOnMissingBean
	public GeminiAgentOptions geminiAgentOptions(GeminiAgentProperties properties) {
		logger.debug("Creating GeminiAgentOptions with yolo={}, model={}", properties.isYolo(), properties.getModel());

		return GeminiAgentOptions.builder()
			.model(properties.getModel())
			.timeout(properties.getTimeout())
			.yolo(properties.isYolo())
			.executablePath(properties.getExecutablePath())
			.temperature(properties.getTemperature())
			.maxTokens(properties.getMaxTokens())
			.build();
	}

	/**
	 * Creates a GeminiAgentModel bean if none exists. Uses Spring-idiomatic dependency
	 * injection with Sandbox.
	 * @param geminiClient the Gemini CLI client
	 * @param geminiAgentOptions the agent options
	 * @param sandbox the sandbox for execution
	 * @return configured GeminiAgentModel
	 */
	@Bean
	@ConditionalOnMissingBean
	public GeminiAgentModel geminiAgentModel(GeminiClient geminiClient, GeminiAgentOptions geminiAgentOptions,
			Sandbox sandbox) {

		logger.debug("Creating GeminiAgentModel with sandbox: {}", sandbox.getClass().getSimpleName());

		return new GeminiAgentModel(geminiClient, geminiAgentOptions, sandbox);
	}

}