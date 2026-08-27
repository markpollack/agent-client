/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which providers are expected to support a given parity test scenario.
 *
 * <p>
 * When a parity TCK test runs for a provider not listed in this annotation, the test is
 * skipped via JUnit {@code Assumptions.assumeTrue(false)} — reported as "skipped" (not
 * "passed" or "failed") in surefire XML output. The CI summary job can then distinguish
 * PASS / FAIL / NOT_APPLICABLE.
 *
 * <p>
 * Example:
 *
 * <pre>
 * &#64;ProviderCapability(providers = { Provider.CLAUDE, Provider.CODEX, Provider.GEMINI })
 * void testSimpleFileCreationInNonGitDirectory() { ... }
 *
 * &#64;ProviderCapability(providers = { Provider.CLAUDE })
 * void testSessionResumption() { ... }
 * // Codex/Gemini IT: Assumptions.assumeTrue(false) -> surefire "skipped"
 * </pre>
 *
 * @author Spring AI Community
 * @since 0.14.0
 * @see ProviderParityTCK
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProviderCapability {

	/**
	 * The provider keys expected to support this test scenario. Use the constants on
 * {@link Provider} for the providers shipped here, or any {@code String} key for an
 * adapter maintained elsewhere.
	 */
	String[] providers();

}
