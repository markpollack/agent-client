/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.markpollack.agents.model.AgentOptions;

/**
 * Junie-specific {@link AgentOptions}.
 *
 * <h2>Why there is no flag-per-field here</h2>
 *
 * <p>
 * This type carries only the neutral core that {@link AgentOptions} already defines.
 * Everything genuinely specific to the Junie CLI — the BYOK provider and its key, update
 * checks, agent mode, MCP and skill locations — rides {@link #getExtras()} and is
 * translated to {@code --key value} on the command line by {@link JunieAgentModel}.
 *
 * <p>
 * That is a deliberate choice, not laziness. Junie's {@code --help} already lists roughly
 * forty flags and JetBrains adds more with each build; mirroring them into fields here
 * would mean a library release for every one of them. The passthrough map costs a
 * consumer nothing and costs this project no releases:
 *
 * <pre>{@code
 * JunieAgentOptions.builder()
 *     .model("gpt-5.3-codex")
 *     .extra("provider", "openai")
 *     .extra("openai-api-key", System.getenv("OPENAI_API_KEY"))
 *     .extra("skip-update-check", true)
 *     .build();
 * }</pre>
 *
 * <p>
 * A {@code Boolean} extra becomes a bare flag when true and is omitted when false; every
 * other value becomes {@code --key value}. Keys are passed through verbatim without the
 * leading dashes.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class JunieAgentOptions implements AgentOptions {

	private final String model;

	private final String effort;

	private final Duration timeout;

	private final String workingDirectory;

	private final Map<String, String> environmentVariables;

	private final Integer maxTurns;

	private final String systemInstructions;

	private final Map<String, Object> jsonSchema;

	private final String executablePath;

	private final Map<String, Object> extras;

	private JunieAgentOptions(Builder builder) {
		this.model = builder.model;
		this.effort = builder.effort;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.environmentVariables = (builder.environmentVariables != null)
				? Map.copyOf(builder.environmentVariables) : Map.of();
		this.maxTurns = builder.maxTurns;
		this.systemInstructions = builder.systemInstructions;
		this.jsonSchema = (builder.jsonSchema != null) ? Map.copyOf(builder.jsonSchema) : Map.of();
		this.executablePath = builder.executablePath;
		this.extras = Map.copyOf(builder.extras);
	}

	@Override
	public String getModel() {
		return this.model;
	}

	@Override
	public String getEffort() {
		return this.effort;
	}

	@Override
	public Duration getTimeout() {
		return this.timeout;
	}

	@Override
	public String getWorkingDirectory() {
		return this.workingDirectory;
	}

	@Override
	public Map<String, String> getEnvironmentVariables() {
		return this.environmentVariables;
	}

	@Override
	public Integer getMaxTurns() {
		return this.maxTurns;
	}

	@Override
	public String getSystemInstructions() {
		return this.systemInstructions;
	}

	@Override
	public Map<String, Object> getJsonSchema() {
		return this.jsonSchema;
	}

	@Override
	public Map<String, Object> getExtras() {
		return this.extras;
	}

	/**
	 * The {@code junie} executable to launch. When null the command is resolved from
	 * {@code PATH}.
	 */
	public String getExecutablePath() {
		return this.executablePath;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String model;

		private String effort;

		private Duration timeout;

		private String workingDirectory;

		private Map<String, String> environmentVariables;

		private Integer maxTurns;

		private String systemInstructions;

		private Map<String, Object> jsonSchema;

		private String executablePath;

		private final Map<String, Object> extras = new LinkedHashMap<>();

		private Builder() {
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * Reasoning effort. The portable {@code low}/{@code medium}/{@code high} values
		 * are exactly Junie's {@code --effort} values, so this needs no translation.
		 */
		public Builder effort(String effort) {
			this.effort = effort;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder environmentVariables(Map<String, String> environmentVariables) {
			this.environmentVariables = environmentVariables;
			return this;
		}

		public Builder maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder systemInstructions(String systemInstructions) {
			this.systemInstructions = systemInstructions;
			return this;
		}

		public Builder jsonSchema(Map<String, Object> jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		public Builder executablePath(String executablePath) {
			this.executablePath = executablePath;
			return this;
		}

		/**
		 * Add one passthrough CLI flag. The key is the long flag name without leading
		 * dashes; {@code Boolean.TRUE} produces a bare flag.
		 */
		public Builder extra(String key, Object value) {
			this.extras.put(key, value);
			return this;
		}

		public Builder extras(Map<String, Object> extras) {
			if (extras != null) {
				this.extras.putAll(extras);
			}
			return this;
		}

		public JunieAgentOptions build() {
			return new JunieAgentOptions(this);
		}

	}

}
