/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import io.github.markpollack.agents.codexsdk.types.SandboxMode;
import io.github.markpollack.agents.codex.CodexClientProvider;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces a {@link CodexClientProvider} when {@code agent-client-codex} is on the
 * classpath and the build-time property {@code agent-client.lifecycle.codex.enabled} is
 * {@code true}. These runtime properties configure it:
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
 * Unset properties keep the provider's own defaults. The deployment module registers this
 * producer only when the provider is present and enabled.
 */
@Singleton
public class CodexLifecycleProducer {

	static final String PREFIX = "agent-client.lifecycle.codex";

	/**
	 * Creates the provider.
	 * @param workingDirectory the working directory
	 * @param executable the CLI
	 * @param acquisitionDirectory the acquisition directory
	 * @param cliVersion the CLI version recorded with acquisitions; required with an
	 * acquisition directory
	 * @return the provider
	 */
	@Produces
	@Singleton
	public CodexClientProvider codexClientProvider(
			@ConfigProperty(name = PREFIX + ".working-directory") Optional<String> workingDirectory,
			@ConfigProperty(name = PREFIX + ".executable") Optional<String> executable,
			@ConfigProperty(name = PREFIX + ".acquisition-directory") Optional<String> acquisitionDirectory,
			@ConfigProperty(name = PREFIX + ".cli-version") Optional<String> cliVersion,
			@ConfigProperty(name = PREFIX + ".sandbox") Optional<String> sandbox) {
		var builder = CodexClientProvider.builder();
		workingDirectory.ifPresent(directory -> builder.workingDirectory(Path.of(directory)));
		executable.ifPresent(builder::codexPath);
		if (acquisitionDirectory.isPresent()) {
			String version = cliVersion.orElseThrow(() -> new IllegalArgumentException(
					PREFIX + ".acquisition-directory requires " + PREFIX + ".cli-version"));
			builder.acquisitionDirectory(Path.of(acquisitionDirectory.get()), version);
		}
		sandbox.ifPresent(
				mode -> builder.sandbox(SandboxMode.valueOf(mode.trim().toUpperCase(Locale.ROOT).replace('-', '_'))));
		return builder.build();
	}

}
