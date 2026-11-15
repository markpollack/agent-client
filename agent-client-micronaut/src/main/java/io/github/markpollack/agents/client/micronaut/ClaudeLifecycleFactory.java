/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.micronaut;

import java.nio.file.Path;

import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * Creates a {@link ClaudeClientProvider} from {@code agent-client.lifecycle.claude.*}
 * when {@code agent-client-claude} is on the classpath and {@code enabled} is
 * {@code true}:
 * <ul>
 * <li>{@code working-directory}: the agent's working directory (default: the current
 * directory)</li>
 * <li>{@code executable}: the CLI to run</li>
 * <li>{@code acquisition-directory} and {@code cli-version}: where native records are
 * acquired, and the CLI version recorded with them; the directory requires the
 * version</li>
 * </ul>
 * Unset properties keep the provider's own defaults.
 */
@Factory
@Requires(classes = ClaudeClientProvider.class)
@Requires(property = ClaudeLifecycleFactory.PREFIX + ".enabled", value = "true")
public class ClaudeLifecycleFactory {

	static final String PREFIX = "agent-client.lifecycle.claude";

	/**
	 * Creates the provider.
	 * @param workingDirectory the working directory, or null for the default
	 * @param executable the CLI, or null for the default
	 * @param acquisitionDirectory the acquisition directory, or null for none
	 * @param cliVersion the CLI version recorded with acquisitions; required with an
	 * acquisition directory
	 * @return the provider
	 */
	@Singleton
	public ClaudeClientProvider claudeClientProvider(
			@Property(name = PREFIX + ".working-directory") @Nullable String workingDirectory,
			@Property(name = PREFIX + ".executable") @Nullable String executable,
			@Property(name = PREFIX + ".acquisition-directory") @Nullable String acquisitionDirectory,
			@Property(name = PREFIX + ".cli-version") @Nullable String cliVersion) {
		var builder = ClaudeClientProvider.builder();
		if (workingDirectory != null) {
			builder.workingDirectory(Path.of(workingDirectory));
		}
		if (executable != null) {
			builder.claudePath(executable);
		}
		if (acquisitionDirectory != null) {
			if (cliVersion == null) {
				throw new IllegalArgumentException(
						PREFIX + ".acquisition-directory requires " + PREFIX + ".cli-version");
			}
			builder.acquisitionDirectory(Path.of(acquisitionDirectory), cliVersion);
		}
		return builder.build();
	}

}
