/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.markpollack.agents.client.advisor.api.AgentCallAdvisor;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.agents.model.mcp.McpServerCatalog;
import static org.assertj.core.api.Assertions.*;

/**
 * Third-party implementations written against the pre-lifecycle interfaces compile
 * unchanged against the current ones, keep their old behaviour, and refuse lifecycle-only
 * features explicitly instead of silently ignoring them.
 */
class LegacyAgentClientShapeTest {

	/** Only the members that existed before the lifecycle release are implemented. */
	static final class LegacyClient implements AgentClient {

		@Override
		public AgentClientRequestSpec goal() {
			return new LegacySpec();
		}

		@Override
		public AgentClientRequestSpec goal(String goal) {
			return new LegacySpec();
		}

		@Override
		public AgentClientRequestSpec goal(Goal goal) {
			return new LegacySpec();
		}

		@Override
		public AgentClientResponse run(String goalText) {
			return null;
		}

		@Override
		public AgentClientResponse run(String goalText, AgentOptions agentOptions) {
			return null;
		}

		@Override
		public Builder mutate() {
			return new LegacyBuilder();
		}

	}

	static final class LegacySpec implements AgentClient.AgentClientRequestSpec {

		@Override
		public AgentClient.AgentClientRequestSpec goal(String goalContent) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec workingDirectory(Path workingDirectory) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec advisors(AgentCallAdvisor... advisors) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec advisors(List<AgentCallAdvisor> advisors) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec mcpServers(String... serverNames) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec mcpServers(List<String> serverNames) {
			return this;
		}

		@Override
		public AgentClient.AgentClientRequestSpec jsonSchema(Map<String, Object> jsonSchema) {
			return this;
		}

		@Override
		public AgentClientResponse run() {
			return null;
		}

	}

	static final class LegacyBuilder implements AgentClient.Builder {

		@Override
		public AgentClient.Builder defaultOptions(AgentOptions agentOptions) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultWorkingDirectory(Path workingDirectory) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultTimeout(Duration timeout) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultAdvisors(List<AgentCallAdvisor> advisors) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultAdvisor(AgentCallAdvisor advisor) {
			return this;
		}

		@Override
		public AgentClient.Builder mcpServerCatalog(McpServerCatalog catalog) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultMcpServers(String... serverNames) {
			return this;
		}

		@Override
		public AgentClient.Builder defaultMcpServers(List<String> serverNames) {
			return this;
		}

		@Override
		public AgentClient build() {
			return new LegacyClient();
		}

	}

	@Test
	void legacyImplementationsKeepOldBehaviourAndRefuseLifecycleFeaturesExplicitly() {
		AgentClient client = new LegacyBuilder().build();
		client.close();
		assertThatCode(client::close).doesNotThrowAnyException();
		assertThatThrownBy(client::sessions).isInstanceOf(UnsupportedOperationException.class);
		var spec = client.goal("old");
		assertThat(spec.goal("still old")).isSameAs(spec);
		assertThatThrownBy(spec::runAsync).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> spec.executionTimeout(Duration.ofSeconds(1)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> spec.onEvent(event -> {
		})).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> spec.correlation(ApplicationCorrelation.empty()))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> spec.refreshBinding(ConnectionBinding.withoutMcp(Map.of())))
			.isInstanceOf(UnsupportedOperationException.class);
		var builder = client.mutate();
		assertThatThrownBy(() -> builder.setupTimeout(Duration.ofSeconds(1)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> builder.cleanupTimeout(Duration.ofSeconds(1)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> builder.executionRecorder(d -> "ack"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
