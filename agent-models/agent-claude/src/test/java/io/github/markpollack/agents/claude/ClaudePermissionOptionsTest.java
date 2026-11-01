/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.claude.agent.sdk.config.PermissionMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudePermissionOptionsTest {

	@Test
	void explicitFalseOverridesSdkBypassDefault() {
		var options = ClaudeAgentOptions.builder().yolo(false).build();
		try (var model = ClaudeAgentModel.builder().defaultOptions(options).build()) {
			var cli = model.buildCLIOptions(new AgentTaskRequest("read", Path.of("."), options));
			assertThat(cli.permissionMode()).isEqualTo(PermissionMode.DEFAULT);
		}
	}

	@Test
	void modelDefaultFalseAndTrueAreExplicit() {
		for (boolean yolo : new boolean[] { false, true }) {
			try (var model = ClaudeAgentModel.builder()
				.defaultOptions(ClaudeAgentOptions.builder().yolo(yolo).build())
				.build()) {
				var cli = model.buildCLIOptions(new AgentTaskRequest("read", Path.of("."), null));
				assertThat(cli.permissionMode())
					.isEqualTo(yolo ? PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS : PermissionMode.DEFAULT);
			}
		}
	}

	@Test
	void availabilityRetainsNullEmptyAndNamesIndependentlyOfApprovalRules() {
		var options = ClaudeAgentOptions.builder().allowedTools(java.util.List.of("Read")).build();
		try (var model = ClaudeAgentModel.builder().build()) {
			var request = new AgentTaskRequest("read", Path.of("."), options);
			assertThat(model.buildCLIOptions(request).tools()).isNull();
			options.setTools(java.util.List.of());
			assertThat(model.buildCLIOptions(request).tools()).isEmpty();
			var names = new java.util.ArrayList<>(java.util.List.of("Read", "Glob", "Grep"));
			options.setTools(names);
			names.clear();
			var cli = model.buildCLIOptions(request);
			assertThat(cli.tools()).containsExactly("Read", "Glob", "Grep");
			assertThat(cli.allowedTools()).containsExactly("Read");
		}
	}

}
