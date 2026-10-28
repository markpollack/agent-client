/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.codexsdk.types;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Configuration options for Codex CLI execution.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteOptions {

	private final String model;

	private final String reasoningEffort;

	private final Duration timeout;

	private final Path workingDirectory;

	private final List<Path> additionalDirectories;

	private final SandboxMode sandboxMode;

	private final ApprovalPolicy approvalPolicy;

	private final boolean fullAuto;

	private final boolean skipGitCheck;

	private final boolean jsonOutput;

	private final Path outputSchema;

	private final boolean dangerouslyBypassSandbox;

	private ExecuteOptions(Builder builder) {
		this.model = builder.model;
		this.reasoningEffort = builder.reasoningEffort;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.additionalDirectories = List.copyOf(builder.additionalDirectories);
		this.sandboxMode = builder.sandboxMode;
		this.approvalPolicy = builder.approvalPolicy;
		this.fullAuto = builder.fullAuto;
		this.skipGitCheck = builder.skipGitCheck;
		this.jsonOutput = builder.jsonOutput;
		this.outputSchema = builder.outputSchema;
		this.dangerouslyBypassSandbox = builder.dangerouslyBypassSandbox;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ExecuteOptions defaultOptions() {
		return builder().build();
	}

	public String getModel() {
		return model;
	}

	/**
	 * Gets the model reasoning effort. Maps to the {@code model_reasoning_effort} config
	 * override ({@code -c model_reasoning_effort="<value>"} — the CLI has no dedicated
	 * flag). Codex-native values: {@code minimal}, {@code low}, {@code medium},
	 * {@code high}, {@code xhigh}. Null uses the CLI/config default.
	 * @return the reasoning effort, or null if not set
	 */
	public String getReasoningEffort() {
		return reasoningEffort;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public Path getWorkingDirectory() {
		return workingDirectory;
	}

	public List<Path> getAdditionalDirectories() {
		return additionalDirectories;
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

	public boolean isJsonOutput() {
		return jsonOutput;
	}

	public Path getOutputSchema() {
		return outputSchema;
	}

	public boolean isDangerouslyBypassSandbox() {
		return dangerouslyBypassSandbox;
	}

	public static class Builder {

		private String model = "gpt-5.4-mini";

		private String reasoningEffort;

		private Duration timeout = Duration.ofMinutes(3);

		private Path workingDirectory;

		private List<Path> additionalDirectories = List.of();

		private SandboxMode sandboxMode = SandboxMode.WORKSPACE_WRITE;

		private ApprovalPolicy approvalPolicy = ApprovalPolicy.NEVER;

		private boolean fullAuto = true;

		private boolean skipGitCheck = false;

		private boolean jsonOutput = false;

		private Path outputSchema;

		private boolean dangerouslyBypassSandbox = false;

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * Sets the model reasoning effort ({@code minimal}, {@code low}, {@code medium},
		 * {@code high}, {@code xhigh}).
		 */
		public Builder reasoningEffort(String reasoningEffort) {
			this.reasoningEffort = reasoningEffort;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder additionalDirectories(List<Path> additionalDirectories) {
			this.additionalDirectories = additionalDirectories != null ? List.copyOf(additionalDirectories) : List.of();
			return this;
		}

		public Builder sandboxMode(SandboxMode sandboxMode) {
			this.sandboxMode = sandboxMode;
			if (sandboxMode != SandboxMode.WORKSPACE_WRITE) {
				this.fullAuto = false;
			}
			return this;
		}

		public Builder approvalPolicy(ApprovalPolicy approvalPolicy) {
			this.approvalPolicy = approvalPolicy;
			if (approvalPolicy != ApprovalPolicy.NEVER) {
				this.fullAuto = false;
			}
			return this;
		}

		public Builder fullAuto(boolean fullAuto) {
			this.fullAuto = fullAuto;
			if (fullAuto) {
				// Full-auto implies workspace-write and never approval
				this.sandboxMode = SandboxMode.WORKSPACE_WRITE;
				this.approvalPolicy = ApprovalPolicy.NEVER;
				this.dangerouslyBypassSandbox = false;
			}
			return this;
		}

		public Builder skipGitCheck(boolean skipGitCheck) {
			this.skipGitCheck = skipGitCheck;
			return this;
		}

		public Builder jsonOutput(boolean jsonOutput) {
			this.jsonOutput = jsonOutput;
			return this;
		}

		public Builder outputSchema(Path outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		public Builder dangerouslyBypassSandbox(boolean dangerouslyBypassSandbox) {
			this.dangerouslyBypassSandbox = dangerouslyBypassSandbox;
			if (dangerouslyBypassSandbox) {
				this.fullAuto = false;
			}
			return this;
		}

		public ExecuteOptions build() {
			return new ExecuteOptions(this);
		}

	}

}
