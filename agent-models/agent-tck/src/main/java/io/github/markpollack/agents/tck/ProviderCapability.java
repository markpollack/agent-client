/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
	 * The providers expected to support this test scenario.
	 */
	Provider[] providers();

}
