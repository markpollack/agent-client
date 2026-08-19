/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.ampsdk.types;

import java.time.Duration;

/**
 * Options for Amp CLI execute mode operations.
 *
 * @author Spring AI Community
 * @since 0.1.0
 */
public class ExecuteOptions {

	private final boolean dangerouslyAllowAll;

	private final Duration timeout;

	private final String model;

	private ExecuteOptions(Builder builder) {
		this.dangerouslyAllowAll = builder.dangerouslyAllowAll;
		this.timeout = builder.timeout;
		this.model = builder.model;
	}

	public boolean isDangerouslyAllowAll() {
		return dangerouslyAllowAll;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public String getModel() {
		return model;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ExecuteOptions defaultOptions() {
		return builder().build();
	}

	public static final class Builder {

		private boolean dangerouslyAllowAll = true;

		private Duration timeout = Duration.ofMinutes(5);

		private String model;

		private Builder() {
		}

		public Builder dangerouslyAllowAll(boolean dangerouslyAllowAll) {
			this.dangerouslyAllowAll = dangerouslyAllowAll;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public ExecuteOptions build() {
			return new ExecuteOptions(this);
		}

	}

}
