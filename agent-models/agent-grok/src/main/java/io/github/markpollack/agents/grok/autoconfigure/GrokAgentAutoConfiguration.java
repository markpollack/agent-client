/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.grok.autoconfigure;

import io.github.markpollack.agents.acp.AcpAgentModel;
import io.github.markpollack.agents.grok.GrokAcpProfile;
import io.github.markpollack.agents.grok.GrokAgentModel;
import io.github.markpollack.agents.grok.GrokAgentOptions;
import io.github.markpollack.agents.groksdk.GrokClient;
import io.github.markpollack.agents.groksdk.types.ExecuteOptions;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
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

	/**
	 * The Grok adapter, on whichever plane is configured.
	 *
	 * <p>
	 * Both branches return the same {@code AgentModel} contract, and the ACP branch
	 * returns the <em>shared</em> {@link AcpAgentModel} rather than a Grok subclass of
	 * it. That is the whole design decision made visible: what Grok needs beyond the
	 * protocol is a {@link GrokAcpProfile}, not a type of its own.
	 * @param grokClient the CLI client, used only by the CLI plane
	 * @param properties the configured properties
	 * @return the adapter
	 */
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
		if (properties.getTransport() == GrokAgentProperties.Transport.ACP) {
			AcpAgentModel acp = AcpAgentModel.builder(new GrokAcpProfile())
				.command(properties.getExecutablePath())
				.defaultOptions(options)
				.build();
			// AcpAgentModel implements the current AgentApi; AgentModel is the deprecated
			// alias this bean is still typed as. Both methods are delegated explicitly
			// rather than using a `acp::call` method reference, which would quietly
			// substitute the interface default for isAvailable().
			return new AgentModel() {
				@Override
				public AgentResponse call(AgentTaskRequest request) {
					return acp.call(request);
				}

				@Override
				public boolean isAvailable() {
					return acp.isAvailable();
				}
			};
		}
		return new GrokAgentModel(grokClient, options);
	}

}
