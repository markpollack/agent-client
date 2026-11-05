/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerCatalog;

/**
 * Client-level facade for agent interactions, following Spring AI's ChatClient pattern.
 *
 * <p>
 * This interface provides a fluent API for interacting with autonomous agents while
 * hiding the complexity of the underlying model layer. It follows Spring AI's two-layer
 * architecture where AgentClient is the high-level API and AgentApi is the low-level
 * model interface.
 * </p>
 *
 * <p>
 * Use {@link AgentClient#builder(AgentApi)} to prepare an instance.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
public interface AgentClient extends AutoCloseable {

	/**
	 * Retained conversations for providers implementing AgentExecutionProvider.
	 * Implementations compiled against the earlier interface keep working: they have no
	 * sessions, and say so.
	 */
	default AgentSessionRegistry sessions() {
		throw new UnsupportedOperationException(
				"Retained sessions require the lifecycle AgentClient built from an AgentExecutionProvider");
	}

	/**
	 * Stop owned executions and close owned conversations. Does not close the borrowed
	 * provider.
	 */
	@Override
	default void close() {
		// An implementation without a lifecycle owns nothing to release.
	}

	/**
	 * Create a new agent client from an agent API.
	 * @param agentApi the underlying agent API
	 * @return a new agent client
	 */
	static AgentClient create(AgentApi agentApi) {
		return builder(agentApi).build();
	}

	/**
	 * Create a new agent client builder.
	 * @param agentApi the underlying agent API
	 * @return a new builder
	 */
	static Builder builder(AgentApi agentApi) {
		return new DefaultAgentClientBuilder(agentApi);
	}

	/**
	 * Create a new agent client request spec - equivalent to ChatClient.prompt().
	 * @return a new request builder
	 */
	AgentClientRequestSpec goal();

	/**
	 * Create a new agent client builder with the specified goal - equivalent to
	 * ChatClient.prompt(String).
	 * @param goal the task goal content
	 * @return a new request builder
	 */
	AgentClientRequestSpec goal(String goal);

	/**
	 * Create a new agent client builder with the specified goal object - equivalent to
	 * ChatClient.prompt(Prompt).
	 * @param goal the goal object
	 * @return a new request builder
	 */
	AgentClientRequestSpec goal(Goal goal);

	/**
	 * Execute a simple goal and return response - convenience method matching
	 * example-code.md.
	 * @param goalText the task goal content
	 * @return the agent response
	 */
	AgentClientResponse run(String goalText);

	/**
	 * Execute a goal with options and return response - convenience method matching
	 * example-code.md.
	 * @param goalText the task goal content
	 * @param agentOptions the agent options
	 * @return the agent response
	 */
	AgentClientResponse run(String goalText, AgentOptions agentOptions);

	/**
	 * Return a {@link AgentClient.Builder} to create a new {@link AgentClient} whose
	 * settings are replicated from this client.
	 * @return a new builder with settings from this client
	 */
	Builder mutate();

	/**
	 * Request specification builder interface following ChatClient pattern.
	 */
	interface AgentClientRequestSpec {

		/**
		 * Set the goal content for the agent task.
		 * @param goalContent the task goal content
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec goal(String goalContent);

		/**
		 * Set the working directory for the agent task.
		 * @param workingDirectory the working directory path
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec workingDirectory(Path workingDirectory);

		/**
		 * Add advisors for this agent request.
		 * @param advisors the advisors to add
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec advisors(AgentCallAdvisor... advisors);

		/**
		 * Add advisors for this agent request.
		 * @param advisors the advisors to add
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec advisors(List<AgentCallAdvisor> advisors);

		/**
		 * Select MCP servers by name for this request. Names are resolved against the
		 * catalog at {@code run()} time. These are unioned with any default MCP servers
		 * set on the builder.
		 * @param serverNames the MCP server names to activate
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec mcpServers(String... serverNames);

		/**
		 * Select MCP servers by name for this request. Names are resolved against the
		 * catalog at {@code run()} time. These are unioned with any default MCP servers
		 * set on the builder.
		 * @param serverNames the MCP server names to activate
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec mcpServers(List<String> serverNames);

		/**
		 * Set a JSON schema for structured output. When set, the agent model will enforce
		 * that its response conforms to this schema.
		 * @param jsonSchema the JSON schema as a map
		 * @return this request spec for chaining
		 */
		AgentClientRequestSpec jsonSchema(Map<String, Object> jsonSchema);

		/**
		 * Execute the agent task and return the result.
		 *
		 * In agent terminology, we **run a goal** (task execution). This differs from
		 * ChatClient, where you **call a prompt**.
		 * @return the agent response
		 */
		AgentClientResponse run();

		/**
		 * Accept an asynchronous execution. Requires an opt-in lifecycle provider; an
		 * implementation of the earlier interface refuses explicitly rather than
		 * pretending a blocking run was accepted asynchronously.
		 */
		default AgentExecution runAsync() {
			throw new UnsupportedOperationException("Asynchronous execution requires the lifecycle AgentClient");
		}

		/**
		 * Positive execution budget starting at acceptance; distinct from result waiting.
		 */
		default AgentClientRequestSpec executionTimeout(Duration timeout) {
			throw new UnsupportedOperationException("Execution timeouts require the lifecycle AgentClient");
		}

		/** Best effort terminal observation on an isolated bounded lane. */
		default AgentClientRequestSpec onEvent(Consumer<AgentClientEvent> observer) {
			throw new UnsupportedOperationException("Event observation requires the lifecycle AgentClient");
		}

		/** Optional application association; never inferred from provider identities. */
		default AgentClientRequestSpec correlation(ApplicationCorrelation correlation) {
			throw new UnsupportedOperationException("Application correlation requires the lifecycle AgentClient");
		}

		/** Install immutable replacement connection authority for this next execution. */
		default AgentClientRequestSpec refreshBinding(ConnectionBinding replacement) {
			throw new UnsupportedOperationException("Binding refresh requires the lifecycle AgentClient");
		}

	}

	/**
	 * A mutable builder for creating an {@link AgentClient}.
	 */
	interface Builder {

		/**
		 * Set default options for all agent requests.
		 * @param agentOptions default agent options
		 * @return this builder for chaining
		 */
		Builder defaultOptions(AgentOptions agentOptions);

		/**
		 * Set default working directory for all agent requests.
		 * @param workingDirectory default working directory
		 * @return this builder for chaining
		 */
		Builder defaultWorkingDirectory(Path workingDirectory);

		/**
		 * Set default timeout for all agent requests.
		 * @param timeout default timeout
		 * @return this builder for chaining
		 */
		Builder defaultTimeout(Duration timeout);

		/**
		 * Set the list of default advisors to use for all agent requests.
		 * @param advisors the default advisors
		 * @return this builder for chaining
		 */
		Builder defaultAdvisors(List<AgentCallAdvisor> advisors);

		/**
		 * Add a single default advisor to use for all agent requests.
		 * @param advisor the advisor to add
		 * @return this builder for chaining
		 */
		Builder defaultAdvisor(AgentCallAdvisor advisor);

		/**
		 * Set the MCP server catalog for resolving server names to definitions.
		 * @param catalog the MCP server catalog
		 * @return this builder for chaining
		 */
		Builder mcpServerCatalog(McpServerCatalog catalog);

		/**
		 * Set default MCP server names applied to every request.
		 * @param serverNames the default server names
		 * @return this builder for chaining
		 */
		Builder defaultMcpServers(String... serverNames);

		/**
		 * Set default MCP server names applied to every request.
		 * @param serverNames the default server names
		 * @return this builder for chaining
		 */
		Builder defaultMcpServers(List<String> serverNames);

		/**
		 * Create a new {@link AgentClient} with the configured defaults.
		 * @return a new agent client
		 */
		AgentClient build();

		/** Positive execution-owned setup allowance. */
		default Builder setupTimeout(Duration timeout) {
			throw new UnsupportedOperationException("Setup timeouts require the lifecycle AgentClient builder");
		}

		/** Positive cleanup allowance; repeated cancellation cannot restart it. */
		default Builder cleanupTimeout(Duration timeout) {
			throw new UnsupportedOperationException("Cleanup timeouts require the lifecycle AgentClient builder");
		}

		/**
		 * Optional secondary decision sink returning its own nonempty receipt ID. Its
		 * failure cannot fail execution.
		 */
		default Builder executionRecorder(java.util.function.Function<ExecutionDecision, String> recorder) {
			throw new UnsupportedOperationException("Execution recording requires the lifecycle AgentClient builder");
		}

	}

}