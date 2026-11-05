/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import io.github.markpollack.agents.claude.autoconfigure.ClaudeAgentAutoConfiguration;
import io.github.markpollack.agents.model.AgentTaskRequest;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Executed inside a class loader that truly lacks journal-core and claude-code-capture;
 * it reports facts as strings so the test outside the loader can assert them.
 */
public final class LegacyAbsentProbe {

	private LegacyAbsentProbe() {
	}

	public static List<String> run(String fakeClaude, String temp) throws Exception {
		List<String> facts = new ArrayList<>();
		try {
			Class.forName("io.github.markpollack.journal.claude.SessionLogParser");
			facts.add("journal:present");
		}
		catch (ClassNotFoundException ex) {
			facts.add("journal:absent");
		}
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ClaudeAgentAutoConfiguration.class))
			.withPropertyValues("agent-client.claude.executable-path=" + fakeClaude)
			.run(context -> facts.add(context.getStartupFailure() == null
					? "boot:ok:" + context.getBean(ClaudeAgentModel.class).getClass().getSimpleName()
					: "boot:failed:" + context.getStartupFailure()));
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ClaudeAgentAutoConfiguration.class))
			.withPropertyValues("agent-client.claude.executable-path=" + fakeClaude,
					"agent-client.claude.trace-dir=" + Path.of(temp, "traces"))
			.run(context -> facts.add("boot-trace:"
					+ (context.getStartupFailure() == null ? "started" : rootMessage(context.getStartupFailure()))));
		try {
			ClaudeAgentModel.builder().traceDir(Path.of(temp, "traces")).build().close();
			facts.add("builder-trace:built");
		}
		catch (IllegalStateException ex) {
			facts.add("builder-trace:" + ex.getMessage());
		}
		try (var model = ClaudeAgentModel.builder()
			.claudePath(fakeClaude)
			.timeout(Duration.ofSeconds(5))
			.defaultOptions(ClaudeAgentOptions.builder().model("test-model").yolo(true).build())
			.build()) {
			var response = model.call(new AgentTaskRequest("find sentinel", Path.of(temp), null));
			facts.add("call:" + response.getResult().getOutput() + ":phaseCapture="
					+ response.getMetadata().<Object>get("phaseCapture") + ":finish="
					+ response.getResult().getMetadata().getFinishReason());
		}
		return facts;
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root.getMessage();
	}

}
