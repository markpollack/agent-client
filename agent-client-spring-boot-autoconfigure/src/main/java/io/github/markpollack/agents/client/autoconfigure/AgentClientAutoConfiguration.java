/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.autoconfigure;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.model.AgentApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * Spring Boot auto-configuration for {@link AgentClient}.
 *
 * <p>
 * This provides a prototype-scoped {@link AgentClient.Builder} bean, following Spring
 * AI's ChatClient.Builder pattern. Each injection point receives a newly cloned instance
 * of the builder.
 *
 * <p>
 * Requires an {@link AgentApi} bean to be present in the application context, which is
 * typically provided by a provider-specific autoconfiguration (e.g.,
 * ClaudeAgentAutoConfiguration).
 *
 * <p>
 * Runs after every provider autoconfiguration, named here so this module needs no
 * provider dependency. Without that ordering, {@code @ConditionalOnBean} is evaluated
 * before a provider whose class name sorts later has registered its agent, and no builder
 * is created.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
@AutoConfiguration(afterName = { "io.github.markpollack.agents.amazonq.autoconfigure.AmazonQAgentAutoConfiguration",
		"io.github.markpollack.agents.amp.autoconfigure.AmpAgentAutoConfiguration",
		"io.github.markpollack.agents.antigravity.autoconfigure.AntigravityAgentAutoConfiguration",
		"io.github.markpollack.agents.claude.autoconfigure.ClaudeAgentAutoConfiguration",
		"io.github.markpollack.agents.claude.autoconfigure.ClaudeHookAutoConfiguration",
		"io.github.markpollack.agents.codex.autoconfigure.CodexAgentAutoConfiguration",
		"io.github.markpollack.agents.gemini.autoconfigure.GeminiAgentAutoConfiguration",
		"io.github.markpollack.agents.grok.autoconfigure.GrokAgentAutoConfiguration",
		"io.github.markpollack.agents.junie.autoconfigure.JunieAgentAutoConfiguration",
		"io.github.markpollack.agents.qwencode.autoconfigure.QwenCodeAgentAutoConfiguration" })
@ConditionalOnClass(AgentClient.class)
@ConditionalOnBean(AgentApi.class)
public class AgentClientAutoConfiguration {

	/**
	 * Creates an AgentClient.Builder with prototype scope.
	 * @param agentApi the configured agent API
	 * @return a new builder instance for each injection point
	 */
	@Bean
	@Scope("prototype")
	@ConditionalOnMissingBean
	public AgentClient.Builder agentClientBuilder(AgentApi agentApi) {
		return AgentClient.builder(agentApi);
	}

}
