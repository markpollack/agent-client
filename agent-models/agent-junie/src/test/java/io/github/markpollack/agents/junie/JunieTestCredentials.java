/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

/**
 * How the Junie integration tests reach a model.
 *
 * <p>
 * Junie authenticates against a JetBrains account and then needs a model behind it.
 * {@code JUNIE_API_KEY} is the CI path — it is read from the environment by the CLI
 * itself, which is what makes Junie the first provider here that can be wired into the
 * parity matrix without a self-hosted runner. A developer without a JetBrains AI
 * subscription can instead point Junie at their own model with a BYOK key, which is why
 * the fallback exists.
 */
final class JunieTestCredentials {

	private JunieTestCredentials() {
	}

	static boolean available() {
		return env("JUNIE_API_KEY") != null || byokProvider() != null;
	}

	/**
	 * Apply whichever credential is present. {@code JUNIE_API_KEY} needs nothing here —
	 * the CLI reads it from the inherited environment.
	 */
	static void apply(JunieAgentOptions.Builder options) {
		if (env("JUNIE_API_KEY") != null) {
			return;
		}
		String provider = byokProvider();
		if (provider == null) {
			return;
		}
		options.extra("provider", provider).extra(provider + "-api-key", env(byokEnvVar(provider)));
		if (env("JUNIE_TEST_MODEL") != null) {
			options.model(env("JUNIE_TEST_MODEL"));
		}
	}

	private static String byokProvider() {
		for (String provider : new String[] { "anthropic", "openai", "google", "openrouter" }) {
			if (env(byokEnvVar(provider)) != null) {
				return provider;
			}
		}
		return null;
	}

	private static String byokEnvVar(String provider) {
		return switch (provider) {
			case "anthropic" -> "ANTHROPIC_API_KEY";
			case "openai" -> "OPENAI_API_KEY";
			case "google" -> "GOOGLE_API_KEY";
			case "openrouter" -> "OPENROUTER_API_KEY";
			default -> provider.toUpperCase() + "_API_KEY";
		};
	}

	private static String env(String name) {
		String value = System.getenv(name);
		return (value != null && !value.isBlank()) ? value : null;
	}

}
