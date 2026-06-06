/*
 * Copyright 2025 Spring AI Community
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
package io.github.markpollack.agents.claude.autoconfigure;

import io.github.markpollack.agents.claude.ClaudeAgentModel;
import io.github.markpollack.agents.claude.ClaudeAgentOptions;
import io.github.markpollack.claude.agent.sdk.hooks.HookRegistry;
import io.github.markpollack.claude.agent.sdk.mcp.McpServerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Map;

/**
 * Spring Boot auto-configuration for Claude Code agent model.
 *
 * <p>
 * Provides automatic configuration of Claude Code agents following Spring AI patterns.
 * The agent model integrates with Claude Code CLI for AI-powered development tasks,
 * supporting blocking, streaming, and iterator-based programming models.
 *
 * @author Spring AI Community
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(ClaudeAgentModel.class)
@EnableConfigurationProperties({ ClaudeAgentProperties.class, ClaudeAgentMcpProperties.class })
public class ClaudeAgentAutoConfiguration {

	/**
	 * Creates a Claude Code agent model with automatic dependency injection.
	 * <p>
	 * The model implements all three programming styles:
	 * <ul>
	 * <li>{@link io.github.markpollack.agents.model.AgentModel} - Blocking</li>
	 * <li>{@link io.github.markpollack.agents.model.StreamingAgentModel} - Reactive</li>
	 * <li>{@link io.github.markpollack.agents.model.IterableAgentModel} - Iterator</li>
	 * </ul>
	 * @param properties agent configuration properties
	 * @return configured Claude Code agent model
	 */
	@Bean
	@ConditionalOnMissingBean
	public ClaudeAgentModel claudeAgentModel(ClaudeAgentProperties properties, ClaudeAgentMcpProperties mcpProperties,
			ObjectProvider<HookRegistry> hookRegistryProvider) {
		ClaudeAgentOptions.Builder optionsBuilder = ClaudeAgentOptions.builder()
			.model(properties.getModel())
			.timeout(properties.getTimeout())
			.yolo(properties.isYolo())
			.executablePath(properties.getExecutablePath());

		// Extended thinking
		if (properties.getMaxThinkingTokens() != null) {
			optionsBuilder.maxThinkingTokens(properties.getMaxThinkingTokens());
		}

		// Max tokens
		if (properties.getMaxTokens() != null) {
			optionsBuilder.maxTokens(properties.getMaxTokens());
		}

		// System prompt
		if (properties.getSystemPrompt() != null && !properties.getSystemPrompt().isBlank()) {
			optionsBuilder.systemPrompt(properties.getSystemPrompt());
		}

		// Tool filtering
		if (properties.getAllowedTools() != null && !properties.getAllowedTools().isEmpty()) {
			optionsBuilder.allowedTools(properties.getAllowedTools());
		}
		if (properties.getDisallowedTools() != null && !properties.getDisallowedTools().isEmpty()) {
			optionsBuilder.disallowedTools(properties.getDisallowedTools());
		}

		// Structured output
		if (properties.getJsonSchema() != null && !properties.getJsonSchema().isEmpty()) {
			optionsBuilder.jsonSchema(properties.getJsonSchema());
		}

		// MCP servers from YAML configuration
		Map<String, McpServerConfig> mcpServers = mcpProperties.toMcpServerConfigs();
		if (!mcpServers.isEmpty()) {
			optionsBuilder.mcpServers(mcpServers);
		}

		ClaudeAgentOptions options = optionsBuilder.build();

		ClaudeAgentModel.Builder builder = ClaudeAgentModel.builder()
			.timeout(properties.getTimeout())
			.defaultOptions(options);

		if (properties.getExecutablePath() != null) {
			builder.claudePath(properties.getExecutablePath());
		}

		// Trace directory + content capture policy
		if (StringUtils.hasText(properties.getTraceDir())) {
			builder.traceDir(Path.of(properties.getTraceDir()));
		}
		if (properties.getTraceContentMode() != null) {
			builder.traceContentMode(properties.getTraceContentMode());
		}
		if (properties.getArchiveTranscript() != null) {
			builder.archiveTranscript(properties.getArchiveTranscript());
		}

		// Inject hook registry if available (from ClaudeHookAutoConfiguration)
		hookRegistryProvider.ifAvailable(builder::hookRegistry);

		return builder.build();
	}

}