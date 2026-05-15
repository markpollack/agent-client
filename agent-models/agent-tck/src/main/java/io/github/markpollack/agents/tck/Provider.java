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

/**
 * Enumerates the agent providers supported by the parity TCK.
 *
 * <p>
 * Used with {@link ProviderCapability} to declare which providers are expected to support
 * a given test scenario. Providers not listed for a scenario are skipped (reported as
 * NOT_APPLICABLE in surefire output) rather than failed.
 *
 * @author Spring AI Community
 * @since 0.14.0
 */
public enum Provider {

	CLAUDE,

	CODEX,

	GEMINI,

	AMAZON_Q,

	AMP,

	QWEN_CODE,

	SWE_AGENT

}
