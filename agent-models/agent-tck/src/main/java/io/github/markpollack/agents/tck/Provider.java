/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

/**
 * Provider keys used by the parity TCK to gate scenarios via {@link ProviderCapability}.
 *
 * <p>
 * This is an <strong>open</strong> extension point. The constants below are the providers
 * shipped by this project, but a provider key is just a {@code String}: an adapter
 * maintained outside this repository declares its own key and participates in the parity
 * suite on equal terms.
 *
 * <pre>
 * public class MyCliParityIT extends ProviderParityTCK {
 *     &#64;Override
 *     protected String getProvider() {
 *         return "MY_CLI";
 *     }
 * }
 * </pre>
 *
 * <p>
 * This was previously a Java {@code enum}, which made it closed: a third party could not
 * add a constant, so every {@link ProviderParityTCK} scenario reported NOT_APPLICABLE for
 * them and the compatibility kit could not actually be used from outside. Because the
 * constants are compile-time {@code String} constants with the same names, existing
 * {@code Provider.CLAUDE}-style references and {@code @ProviderCapability} annotations
 * continue to compile unchanged.
 *
 * <p>
 * Keys are compared exactly; by convention they are upper snake case.
 *
 * @author Spring AI Community
 * @since 0.14.0
 * @see ProviderCapability
 * @see ProviderParityTCK
 */
public final class Provider {

	public static final String CLAUDE = "CLAUDE";

	public static final String CODEX = "CODEX";

	public static final String GEMINI = "GEMINI";

	public static final String AMAZON_Q = "AMAZON_Q";

	public static final String AMP = "AMP";

	public static final String QWEN_CODE = "QWEN_CODE";

	public static final String GROK = "GROK";

	public static final String ANTIGRAVITY = "ANTIGRAVITY";

	public static final String SWE_AGENT = "SWE_AGENT";

	public static final String JUNIE = "JUNIE";

	private Provider() {
	}

}
