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

	ExecutionRuntime runtime = new ExecutionRuntime(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(10));

	java.util.function.Function<ExecutionDecision, String> recorder;

	java.time.Duration defaultExecutionTimeout;

	private final java.util.Set<DefaultAgentSession> ownedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final Object lifecycleLock = new Object();

	private boolean closed;

	private AgentExecutionProvider lifecycleProvider() {
		if (this.agentApi instanceof AgentExecutionProvider provider)
			return provider;
		throw new UnsupportedOperationException("Provider has no qualified async/retained Client lifecycle adapter");
	}

	@Override
	public AgentSessionRegistry sessions() {
		lifecycleProvider();
		return configure -> {
			var spec = new DefaultSessionSpec(this.defaultOptions);
			java.util.Objects.requireNonNull(configure, "configure").accept(spec);
			var options = spec.binding == null ? ExecutionOptions.copy(spec.options)
					: spec.binding.apply(spec.options, true);
			var session = ownSession(options, true);
			session.binding = spec.binding;
			return SessionAcquisition.open(session);
		};
	}

	private DefaultAgentSession ownSession(AgentOptions options, boolean retained) {
		synchronized (this.lifecycleLock) {
			if (this.closed)
				throw new IllegalStateException("Client is closed");
			var session = new DefaultAgentSession(this, lifecycleProvider(), options, retained);
			this.ownedSessions.add(session);
			return session;
		}
	}

	void forget(DefaultAgentSession session) {
		this.ownedSessions.remove(session);
	}

	AgentClientRequestSpec sessionGoal(DefaultAgentSession session, String prompt) {
		var spec = new DefaultAgentClientRequestSpec(new Goal(prompt, null, session.options));
		spec.session = session;
		return spec;
	}

	private CleanupBudget closeBudget;

	@Override
	public void close() {
		ExecutionContext.checkClose(this, null);
		java.util.List<DefaultAgentSession> sessions;
		CleanupBudget budget;
		synchronized (this.lifecycleLock) {
			this.closed = true;
			if (this.closeBudget == null)
				this.closeBudget = new CleanupBudget(this.runtime.cleanupNanos);
			budget = this.closeBudget;
			sessions = java.util.List.copyOf(this.ownedSessions);
		}
		sessions.forEach(session -> session.requestClose(budget));
		// Sessions settle first, so a decision made during close still reaches the
		// recorder
		// lane (S9); the lanes stop only after that, within the same budget.
		awaitSessions(sessions);
		this.runtime.stopOwnedLanes();
		awaitOwnedLanes(budget);
		if (!this.ownedSessions.isEmpty() || !this.runtime.remainingLanes().isEmpty())
			throw new CleanupUnconfirmedException("Client cleanup unconfirmed", new CleanupHandle() {
				@Override
				public String id() {
					return "client";
				}

				@Override
				public CleanupSnapshot inspect() {
					var remaining = new java.util.ArrayList<>(
							ownedSessions.stream().map(DefaultAgentSession::id).toList());
					remaining.addAll(runtime.remainingLanes());
					return new CleanupSnapshot(id(),
							remaining.isEmpty() ? CleanupState.CONFIRMED : CleanupState.UNKNOWN, remaining,
							java.util.Optional.empty());
				}
			});
	}

	private void awaitSessions(java.util.List<DefaultAgentSession> sessions) {
		boolean interrupted = Thread.interrupted();
		try {
			for (var session : sessions) {
				try {
					session.awaitClose();
				}
				catch (CleanupUnconfirmedException ignored) {
					/* Continue every owned teardown. */ }
				interrupted |= Thread.interrupted();
			}
		}
		finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private void awaitOwnedLanes(CleanupBudget budget) {
		this.runtime.awaitOwnedLanes(budget);
	}

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
		var builder = (DefaultAgentClientBuilder) new DefaultAgentClientBuilder(this.agentApi)
			.defaultOptions(this.defaultOptions)
			.defaultAdvisors(this.defaultAdvisors)
			.mcpServerCatalog(this.mcpServerCatalog)
			.defaultMcpServers(this.defaultMcpServerNames)
			.setupTimeout(java.time.Duration.ofNanos(this.runtime.setupNanos))
			.cleanupTimeout(java.time.Duration.ofNanos(this.runtime.cleanupNanos))
			.executionRecorder(this.recorder);
		builder.executionTimeout = this.defaultExecutionTimeout;
		return builder;
	}

	/**
	 * Default implementation of AgentClientRequestSpec.
	 */
	private class DefaultAgentClientRequestSpec implements AgentClientRequestSpec {

		private Goal goal;

		private DefaultAgentSession session;

		private java.time.Duration executionTimeout;

		private java.util.function.Consumer<AgentClientEvent> observer;

		private ApplicationCorrelation correlation = ApplicationCorrelation.empty();

		private ConnectionBinding replacement;

		@Override
		public AgentClientRequestSpec refreshBinding(ConnectionBinding replacement) {
			this.replacement = java.util.Objects.requireNonNull(replacement);
			return this;
		}

		@Override
		public AgentClientRequestSpec executionTimeout(java.time.Duration timeout) {
			ExecutionTimes.nanos(timeout, true);
			this.executionTimeout = timeout;
			return this;
		}

		@Override
		public AgentClientRequestSpec onEvent(java.util.function.Consumer<AgentClientEvent> observer) {
			this.observer = java.util.Objects.requireNonNull(observer, "observer");
			return this;
		}

		@Override
		public AgentClientRequestSpec correlation(ApplicationCorrelation correlation) {
			this.correlation = java.util.Objects.requireNonNull(correlation, "correlation");
			return this;
		}

		private Path workingDirectory;

		private List<AgentCallAdvisor> requestAdvisors = new ArrayList<>();

		private List<String> mcpServerNames = new ArrayList<>();

		private Map<String, Object> jsonSchema;

		private DefaultAgentClientRequestSpec(Goal goal) {
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
			if (DefaultAgentClient.this.agentApi instanceof AgentExecutionProvider)
				return SynchronousExecution.result(submit());
			return invoke(prepare(), DefaultAgentClient.this.agentApi);
		}

		@Override
		public AgentExecution runAsync() {
			return submit();
		}

		private DefaultAgentExecution submit() {
			lifecycleProvider();
			AgentClientRequest request = prepare();
			if (this.replacement != null)
				request = new AgentClientRequest(request.goal(), request.workingDirectory(),
						this.replacement.apply(request.options(), false), request.context());
			request = new AgentClientRequest(request.goal(), request.workingDirectory(), acceptedOptions(request),
					request.context());
			final AgentClientRequest acceptedRequest = request;
			var advisors = List.copyOf(this.requestAdvisors);
			var owner = this.session == null ? ownSession(request.options(), false) : this.session;
			var work = new ExecutionWork(request.options(),
					this.executionTimeout == null ? DefaultAgentClient.this.defaultExecutionTimeout
							: this.executionTimeout,
					this.correlation, this.replacement, api -> invoke(acceptedRequest, api, advisors), this.observer,
					DefaultAgentClient.this.recorder);
			return owner.submit(work);
		}

		private AgentOptions acceptedOptions(AgentClientRequest request) {
			AgentOptions options = request.options();
			// A retained session already owns its process home. A request directory in
			// that session is application context, not permission to relocate it.
			if (this.session == null && (this.workingDirectory != null || this.goal.getWorkingDirectory() != null
					|| DefaultAgentClient.this.defaultOptions.getWorkingDirectory() != null)) {
				options = DefaultAgentOptions.builder()
					.from(options)
					.workingDirectory(request.workingDirectory().toAbsolutePath().normalize().toString())
					.build();
			}
			return ExecutionOptions.copy(options);
		}

		private AgentClientRequest prepare() {
			synchronized (DefaultAgentClient.this.lifecycleLock) {
				if (DefaultAgentClient.this.closed)
					throw new IllegalStateException("Client is closed");
			}
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

			return request;
		}

		private AgentClientResponse invoke(AgentClientRequest request, AgentApi api) {
			return invoke(request, api, this.requestAdvisors);
		}

		private AgentClientResponse invoke(AgentClientRequest request, AgentApi api,
				List<AgentCallAdvisor> requestedAdvisors) {
			// Build the existing required advisor chain around the lifecycle-owned turn.
			List<AgentCallAdvisor> advisors = new ArrayList<>(DefaultAgentClient.this.defaultAdvisors);
			advisors.addAll(requestedAdvisors);
			advisors.add(new AgentModelCallAdvisor(api));

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