/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codex;

import io.github.markpollack.agents.codexsdk.types.ApprovalPolicy;
import io.github.markpollack.agents.codexsdk.types.SandboxMode;
import io.github.markpollack.agents.model.AgentOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Configuration options for Codex Agent Model implementations.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class CodexAgentOptions implements AgentOptions {

	private String model = "gpt-5.4-mini";

	private String reasoningEffort;

	private Duration timeout = Duration.ofMinutes(10);

	private SandboxMode sandboxMode = SandboxMode.WORKSPACE_WRITE;

	private ApprovalPolicy approvalPolicy = ApprovalPolicy.NEVER;

	private boolean fullAuto = true;

	private boolean skipGitCheck = false;

	private boolean dangerouslyBypassSandbox = false;

	private String executablePath;

	private Path outputSchema;

	private String workingDirectory;

	private Map<String, String> environmentVariables = Map.of();

	private Map<String, Object> extras = Map.of();

	private CodexAgentOptions() {
	}

	public String getModel() {
		return model;
	}

	/**
	 * Gets the model reasoning effort. Maps to Codex's {@code model_reasoning_effort}
	 * config override. Codex-native values: {@code minimal}, {@code low}, {@code medium},
	 * {@code high}, {@code xhigh} (a wider range than the portable
	 * {@code AgentOptions#getEffort()} values). Takes precedence over the portable effort
	 * when both are set.
	 * @return the reasoning effort, or null to use the CLI/config default
	 */
	public String getReasoningEffort() {
		return reasoningEffort;
	}

	public Duration getTimeout() {
		return timeout;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return environmentVariables;
	}

	public SandboxMode getSandboxMode() {
		return sandboxMode;
	}

	public ApprovalPolicy getApprovalPolicy() {
		return approvalPolicy;
	}

	public boolean isFullAuto() {
		return fullAuto;
	}

	public boolean isSkipGitCheck() {
		return skipGitCheck;
	}

	public boolean isDangerouslyBypassSandbox() {
		return dangerouslyBypassSandbox;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public Path getOutputSchema() {
		return outputSchema;
	}

	@Override
	public String getWorkingDirectory() {
		return workingDirectory;
	}

	@Override
	public Map<String, Object> getExtras() {
		return extras;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private final CodexAgentOptions options = new CodexAgentOptions();

		private Builder() {
		}

		public Builder model(String model) {
			options.model = model;
			return this;
		}

		/**
		 * Sets the model reasoning effort ({@code minimal}, {@code low}, {@code medium},
		 * {@code high}, {@code xhigh}).
		 */
		public Builder reasoningEffort(String reasoningEffort) {
			options.reasoningEffort = reasoningEffort;
			return this;
		}

		public Builder timeout(Duration timeout) {
			options.timeout = timeout;
			return this;
		}

		public Builder sandboxMode(SandboxMode sandboxMode) {
			options.sandboxMode = sandboxMode;
			return this;
		}

		public Builder approvalPolicy(ApprovalPolicy approvalPolicy) {
			options.approvalPolicy = approvalPolicy;
			return this;
		}

		public Builder fullAuto(boolean fullAuto) {
			options.fullAuto = fullAuto;
			if (fullAuto) {
				// Full-auto implies workspace-write and never approval
				options.sandboxMode = SandboxMode.WORKSPACE_WRITE;
				options.approvalPolicy = ApprovalPolicy.NEVER;
			}
			return this;
		}

		public Builder skipGitCheck(boolean skipGitCheck) {
			options.skipGitCheck = skipGitCheck;
			return this;
		}

		public Builder dangerouslyBypassSandbox(boolean dangerouslyBypassSandbox) {
			options.dangerouslyBypassSandbox = dangerouslyBypassSandbox;
			return this;
		}

		public Builder executablePath(String executablePath) {
			options.executablePath = executablePath;
			return this;
		}

		public Builder outputSchema(Path outputSchema) {
			options.outputSchema = outputSchema;
			return this;
		}

		public Builder extras(Map<String, Object> extras) {
			options.extras = extras != null ? extras : Map.of();
			return this;
		}

		public CodexAgentOptions build() {
			return options;
		}

	}

}
