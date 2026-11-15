/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.micronaut;

import java.nio.file.Path;
import java.util.Locale;

import io.github.markpollack.agents.codexsdk.types.SandboxMode;
import io.github.markpollack.agents.codex.CodexClientProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * Creates a {@link CodexClientProvider} from {@code agent-client.lifecycle.codex.*} when
 * {@code agent-client-codex} is on the classpath and {@code enabled} is {@code true}:
 * <ul>
 * <li>{@code working-directory}: the agent's working directory (default: the current
 * directory)</li>
 * <li>{@code executable}: the CLI to run</li>
 * <li>{@code acquisition-directory} and {@code cli-version}: where native records are
 * acquired, and the CLI version recorded with them; the directory requires the
 * version</li>
 * <li>{@code sandbox}: {@code read-only} (the default), {@code workspace-write} or
 * {@code danger-full-access}</li>
 * </ul>
 * Unset properties keep the provider's own defaults.
 */
@Factory
@Requires(classes = CodexClientProvider.class)
@Requires(property = CodexLifecycleFactory.PREFIX + ".enabled", value = "true")
public class CodexLifecycleFactory {

	static final String PREFIX = "agent-client.lifecycle.codex";

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
	public CodexClientProvider codexClientProvider(
			@Property(name = PREFIX + ".working-directory") @Nullable String workingDirectory,
			@Property(name = PREFIX + ".executable") @Nullable String executable,
			@Property(name = PREFIX + ".acquisition-directory") @Nullable String acquisitionDirectory,
			@Property(name = PREFIX + ".cli-version") @Nullable String cliVersion,
			@Property(name = PREFIX + ".sandbox") @Nullable String sandbox) {
		var builder = CodexClientProvider.builder();
		if (workingDirectory != null) {
			builder.workingDirectory(Path.of(workingDirectory));
		}
		if (executable != null) {
			builder.codexPath(executable);
		}
		if (acquisitionDirectory != null) {
			if (cliVersion == null) {
				throw new IllegalArgumentException(
						PREFIX + ".acquisition-directory requires " + PREFIX + ".cli-version");
			}
			builder.acquisitionDirectory(Path.of(acquisitionDirectory), cliVersion);
		}
		if (sandbox != null) {
			builder.sandbox(SandboxMode.valueOf(sandbox.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
		}
		return builder.build();
	}

}
