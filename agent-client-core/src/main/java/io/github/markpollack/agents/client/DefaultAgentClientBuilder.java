/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerCatalog;

/**
 * Default implementation of {@link AgentClient.Builder}.
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public class DefaultAgentClientBuilder implements AgentClient.Builder {

	private final AgentApi agentApi;

	private AgentOptions defaultOptions;

	private List<AgentCallAdvisor> defaultAdvisors;

	private McpServerCatalog mcpServerCatalog;

	private List<String> defaultMcpServerNames = new ArrayList<>();

	public DefaultAgentClientBuilder(AgentApi agentApi) {
		this.agentApi = Objects.requireNonNull(agentApi, "AgentApi cannot be null");
		this.defaultOptions = new DefaultAgentOptions();
		this.defaultAdvisors = new ArrayList<>();
	}

	@Override
	public AgentClient.Builder defaultOptions(AgentOptions agentOptions) {
		this.defaultOptions = agentOptions != null ? agentOptions : new DefaultAgentOptions();
		return this;
	}

	@Override
	public AgentClient.Builder defaultWorkingDirectory(Path workingDirectory) {
		// Build new options with the working directory
		this.defaultOptions = DefaultAgentOptions.builder()
			.from(this.defaultOptions)
			.workingDirectory(workingDirectory != null ? workingDirectory.toString() : null)
			.build();
		return this;
	}

	@Override
	public AgentClient.Builder defaultTimeout(Duration timeout) {
		// Build new options with the timeout
		this.defaultOptions = DefaultAgentOptions.builder().from(this.defaultOptions).timeout(timeout).build();
		return this;
	}

	@Override
	public AgentClient.Builder defaultAdvisors(List<AgentCallAdvisor> advisors) {
		this.defaultAdvisors = advisors != null ? new ArrayList<>(advisors) : new ArrayList<>();
		return this;
	}

	@Override
	public AgentClient.Builder defaultAdvisor(AgentCallAdvisor advisor) {
		if (advisor != null) {
			this.defaultAdvisors.add(advisor);
		}
		return this;
	}

	@Override
	public AgentClient.Builder mcpServerCatalog(McpServerCatalog catalog) {
		this.mcpServerCatalog = catalog;
		return this;
	}

	@Override
	public AgentClient.Builder defaultMcpServers(String... serverNames) {
		this.defaultMcpServerNames = serverNames != null ? new ArrayList<>(Arrays.asList(serverNames))
				: new ArrayList<>();
		return this;
	}

	@Override
	public AgentClient.Builder defaultMcpServers(List<String> serverNames) {
		this.defaultMcpServerNames = serverNames != null ? new ArrayList<>(serverNames) : new ArrayList<>();
		return this;
	}

	@Override
	public AgentClient build() {
		return new DefaultAgentClient(this.agentApi, this.defaultOptions, this.defaultAdvisors, this.mcpServerCatalog,
				this.defaultMcpServerNames);
	}

}