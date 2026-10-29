/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.transport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import io.github.markpollack.agents.codexsdk.types.ApprovalPolicy;
import io.github.markpollack.agents.codexsdk.types.ExecuteOptions;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;

/** Constructs ordered global and exec-specific Codex arguments. */
final class CodexCommandBuilder {

	private CodexCommandBuilder() {
	}

	static List<String> build(String codexCliPath, String prompt, ExecuteOptions options, String sessionId) {
		List<String> command = new ArrayList<>();

		// Base command. Full-auto used to have a convenience flag, but current Codex
		// versions express that contract with global sandbox and approval options. Global
		// options must precede the exec subcommand.
		command.add(codexCliPath);
		addPermissions(command, options);

		addGlobalOverrides(command, options);

		command.add("exec");
		if (sessionId != null && !sessionId.isEmpty()) {
			command.add("resume");
		}

		addExecutionOptions(command, options);

		addOutputOptions(command, options);

		// Session resume
		if (sessionId != null && !sessionId.isEmpty()) {
			command.add(sessionId);
		}

		// End of flags, prompt as final argument
		command.add("--");
		command.add(prompt);

		return command;
	}

	private static void addPermissions(List<String> command, ExecuteOptions options) {
		if (options.isFullAuto() && !options.isDangerouslyBypassSandbox()) {
			command.add("--sandbox");
			command.add(SandboxMode.WORKSPACE_WRITE.getValue());
			command.add("--ask-for-approval");
			command.add(ApprovalPolicy.NEVER.getValue());
		}

		if (!options.isFullAuto() && !options.isDangerouslyBypassSandbox()) {
			command.add("--ask-for-approval");
			command.add(options.getApprovalPolicy().getValue());
			command.add("--sandbox");
			command.add(options.getSandboxMode().getValue());
		}
	}

	private static void addGlobalOverrides(List<String> command, ExecuteOptions options) {
		// Global overrides are repeated on every invocation, including exact-thread
		// resume.
		options.getConfigOverrides().forEach((key, value) -> {
			command.add("-c");
			command.add(key + "=" + value);
		});
		// Working directory via -C flag
		if (options.getWorkingDirectory() != null) {
			command.add("-C");
			command.add(options.getWorkingDirectory().toString());
		}
	}

	private static void addExecutionOptions(List<String> command, ExecuteOptions options) {
		// Model is an exec-specific option (not a global flag)
		if (options.getModel() != null && !options.getModel().isEmpty()) {
			command.add("--model");
			command.add(options.getModel());
		}

		// Reasoning effort: no dedicated flag — uses the -c config override. The value
		// is quoted so it parses as a TOML string.
		if (options.getReasoningEffort() != null && !options.getReasoningEffort().isEmpty()) {
			command.add("-c");
			command.add("model_reasoning_effort=\"" + options.getReasoningEffort() + "\"");
		}

		// Execution mode
		if (options.isDangerouslyBypassSandbox()) {
			command.add("--dangerously-bypass-approvals-and-sandbox");
		}

		for (Path additionalDirectory : options.getAdditionalDirectories()) {
			command.add("--add-dir");
			command.add(additionalDirectory.toString());
		}

	}

	private static void addOutputOptions(List<String> command, ExecuteOptions options) {
		// Git check skip
		if (options.isSkipGitCheck()) {
			command.add("--skip-git-repo-check");
		}

		// JSON output mode
		if (options.isJsonOutput()) {
			command.add("--json");
		}

		// Output schema for structured output
		if (options.getOutputSchema() != null) {
			command.add("--output-schema");
			command.add(options.getOutputSchema().toString());
		}

	}

}
