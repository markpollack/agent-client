/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.markpollack.agents.client.advisor.AgentModelCallAdvisor;
import io.github.markpollack.agents.client.advisor.DefaultAgentCallAdvisorChain;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentModel;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.agents.model.mcp.McpServerCatalog;
import io.github.markpollack.agents.model.mcp.McpServerDefinition;

/**
 * Default implementation of AgentClient following Spring AI patterns.
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public class DefaultAgentClient implements AgentClient {

	private final AgentApi agentApi;

	private final AgentOptions defaultOptions;

	private final List<AgentCallAdvisor> defaultAdvisors;

	private final McpServerCatalog mcpServerCatalog;

	private final List<String> defaultMcpServerNames;

	/**
	 * Create a new DefaultAgentClient with the given agent API.
	 * @param agentApi the underlying agent API
	 */
	public DefaultAgentClient(AgentApi agentApi) {
		this(agentApi, new DefaultAgentOptions(), new ArrayList<>(), null, List.of());
	}

	/**
	 * Create a new DefaultAgentClient with the given agent API and default options.
	 * @param agentApi the underlying agent API
	 * @param defaultOptions default options for all requests
	 */
	public DefaultAgentClient(AgentApi agentApi, AgentOptions defaultOptions) {
		this(agentApi, defaultOptions, new ArrayList<>(), null, List.of());
	}

	/**
	 * Create a new DefaultAgentClient with the given agent API, default options, and
	 * advisors.
	 * @param agentApi the underlying agent API
	 * @param defaultOptions default options for all requests
	 * @param defaultAdvisors default advisors for all requests
	 */
	public DefaultAgentClient(AgentApi agentApi, AgentOptions defaultOptions, List<AgentCallAdvisor> defaultAdvisors) {
		this(agentApi, defaultOptions, defaultAdvisors, null, List.of());
	}

	/**
	 * Create a new DefaultAgentClient with the given agent API, default options,
	 * advisors, and MCP catalog.
	 * @param agentApi the underlying agent API
	 * @param defaultOptions default options for all requests
	 * @param defaultAdvisors default advisors for all requests
	 * @param mcpServerCatalog the MCP server catalog, may be null
	 * @param defaultMcpServerNames default MCP server names for all requests
	 */
	public DefaultAgentClient(AgentApi agentApi, AgentOptions defaultOptions, List<AgentCallAdvisor> defaultAdvisors,
			McpServerCatalog mcpServerCatalog, List<String> defaultMcpServerNames) {
		this.agentApi = Objects.requireNonNull(agentApi, "AgentApi cannot be null");
		this.defaultOptions = defaultOptions != null ? defaultOptions : new DefaultAgentOptions();
		this.defaultAdvisors = defaultAdvisors != null ? new ArrayList<>(defaultAdvisors) : new ArrayList<>();
		this.mcpServerCatalog = mcpServerCatalog;
		this.defaultMcpServerNames = defaultMcpServerNames != null ? List.copyOf(defaultMcpServerNames) : List.of();
	}

	@Override
	public AgentClientRequestSpec goal() {
		return new DefaultAgentClientRequestSpec(null);
	}

	@Override
	public AgentClientRequestSpec goal(String goal) {
		return goal(new Goal(goal));
	}

	@Override
	public AgentClientRequestSpec goal(Goal goal) {
		return new DefaultAgentClientRequestSpec(goal);
	}

	@Override
	public AgentClientResponse run(String goalText) {
		return goal(goalText).run();
	}

	@Override
	public AgentClientResponse run(String goalText, AgentOptions agentOptions) {
		Goal goal = new Goal(goalText, null, agentOptions);
		return goal(goal).run();
	}

	@Override
	public AgentClient.Builder mutate() {
		return new DefaultAgentClientBuilder(this.agentApi).defaultOptions(this.defaultOptions)
			.defaultAdvisors(this.defaultAdvisors)
			.mcpServerCatalog(this.mcpServerCatalog)
			.defaultMcpServers(this.defaultMcpServerNames);
	}

	/**
	 * Default implementation of AgentClientRequestSpec.
	 */
	private class DefaultAgentClientRequestSpec implements AgentClientRequestSpec {

		private Goal goal;

		private Path workingDirectory;

		private List<AgentCallAdvisor> requestAdvisors = new ArrayList<>();

		private List<String> mcpServerNames = new ArrayList<>();

		private Map<String, Object> jsonSchema;

		public DefaultAgentClientRequestSpec(Goal goal) {
			this.goal = goal; // Can be null for goal() method
			this.workingDirectory = goal != null ? goal.getWorkingDirectory() : null;
		}

		@Override
		public AgentClientRequestSpec goal(String goalContent) {
			this.goal = new Goal(goalContent);
			return this;
		}

		@Override
		public AgentClientRequestSpec workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		@Override
		public AgentClientRequestSpec advisors(AgentCallAdvisor... advisors) {
			this.requestAdvisors.addAll(Arrays.asList(advisors));
			return this;
		}

		@Override
		public AgentClientRequestSpec advisors(List<AgentCallAdvisor> advisors) {
			this.requestAdvisors.addAll(advisors);
			return this;
		}

		@Override
		public AgentClientRequestSpec mcpServers(String... serverNames) {
			if (serverNames != null) {
				this.mcpServerNames.addAll(Arrays.asList(serverNames));
			}
			return this;
		}

		@Override
		public AgentClientRequestSpec mcpServers(List<String> serverNames) {
			if (serverNames != null) {
				this.mcpServerNames.addAll(serverNames);
			}
			return this;
		}

		@Override
		public AgentClientRequestSpec jsonSchema(Map<String, Object> jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		@Override
		public AgentClientResponse run() {
			// Ensure we have a goal before proceeding
			if (this.goal == null) {
				throw new IllegalStateException(
						"Goal must be set before running. Use goal(String) or goal(Goal) first.");
			}

			// Determine effective working directory
			Path effectiveWorkingDirectory = determineWorkingDirectory();

			// Merge options
			AgentOptions effectiveOptions = mergeOptions(this.goal.getOptions(),
					DefaultAgentClient.this.defaultOptions);

			// Resolve MCP server names to definitions via the catalog
			effectiveOptions = resolveMcpServers(effectiveOptions);

			// Inject per-request jsonSchema if set (only override when explicitly set)
			if (this.jsonSchema != null) {
				effectiveOptions = DefaultAgentOptions.builder()
					.from(effectiveOptions)
					.jsonSchema(this.jsonSchema)
					.build();
			}

			// Create client-layer request
			AgentClientRequest request = new AgentClientRequest(this.goal, effectiveWorkingDirectory, effectiveOptions,
					new HashMap<>());

			// Build advisor chain with terminal advisor
			List<AgentCallAdvisor> advisors = new ArrayList<>(DefaultAgentClient.this.defaultAdvisors);
			advisors.addAll(this.requestAdvisors);
			advisors.add(new AgentModelCallAdvisor(DefaultAgentClient.this.agentApi));

			var chain = DefaultAgentCallAdvisorChain.builder().pushAll(advisors).build();

			// Execute through advisor chain
			return chain.nextCall(request);
		}

		private Path determineWorkingDirectory() {
			// Use working directory priority: explicit > goal > builder default > current
			// directory
			if (this.workingDirectory != null) {
				// Explicit working directory was set on this request
				return this.workingDirectory;
			}
			else if (this.goal.getWorkingDirectory() != null) {
				// Use working directory from goal
				return this.goal.getWorkingDirectory();
			}
			else if (DefaultAgentClient.this.defaultOptions.getWorkingDirectory() != null) {
				// Use default working directory from builder
				return Path.of(DefaultAgentClient.this.defaultOptions.getWorkingDirectory());
			}
			else {
				// Fall back to current working directory
				return Path.of(System.getProperty("user.dir"));
			}
		}

		private AgentOptions mergeOptions(AgentOptions goalOptions, AgentOptions defaultOptions) {
			// If goal has no options, use defaults
			if (goalOptions == null) {
				return defaultOptions;
			}

			// If no defaults, use goal options
			if (defaultOptions == null) {
				return goalOptions;
			}

			// Both exist - goal options take precedence
			// For now, just return goal options since merging complex
			// TODO: Implement proper options merging if needed
			return goalOptions;
		}

		private AgentOptions resolveMcpServers(AgentOptions options) {
			// Union builder defaults + request spec names, deduplicated
			Set<String> allNames = new LinkedHashSet<>(DefaultAgentClient.this.defaultMcpServerNames);
			allNames.addAll(this.mcpServerNames);

			if (allNames.isEmpty()) {
				return options;
			}

			// Fail-fast: names present but no catalog
			if (DefaultAgentClient.this.mcpServerCatalog == null) {
				throw new IllegalStateException("MCP server names were requested " + allNames
						+ " but no McpServerCatalog is configured on the AgentClient builder. "
						+ "Use AgentClient.builder(model).mcpServerCatalog(catalog) to set one.");
			}

			// Resolve names to definitions
			Map<String, McpServerDefinition> resolved = DefaultAgentClient.this.mcpServerCatalog.resolve(allNames);

			// Build new options carrying the resolved definitions
			return DefaultAgentOptions.builder().from(options).mcpServerDefinitions(resolved).build();
		}

	}

}