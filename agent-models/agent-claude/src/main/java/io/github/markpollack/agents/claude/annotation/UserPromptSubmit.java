/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.claude.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a user-prompt-submit hook that is called when a user prompt is
 * submitted.
 *
 * <p>
 * The annotated method will be invoked when a user submits a prompt to Claude. The method
 * can inspect, validate, or modify the prompt before it is processed.
 * </p>
 *
 * <p>
 * Method signature requirements:
 * </p>
 * <ul>
 * <li>Parameter: {@link io.github.markpollack.claude.agent.sdk.types.control.HookInput}
 * or
 * {@link io.github.markpollack.claude.agent.sdk.types.control.HookInput.UserPromptSubmitInput}</li>
 * <li>Return type:
 * {@link io.github.markpollack.claude.agent.sdk.types.control.HookOutput} or {@code void}
 * (void implies allow)</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * &#64;Component
 * public class PromptValidationHooks {
 *

 *     &#64;UserPromptSubmit
 *     public HookOutput validatePrompt(HookInput.UserPromptSubmitInput input) {
 *         String prompt = input.prompt();
 *         if (prompt.contains("password") || prompt.contains("secret")) {
 *             return HookOutput.block("Prompt contains sensitive keywords");
 *         }
 *         return HookOutput.allow();
 *     }
 *
 *
&#64;UserPromptSubmit
 *     public void logPrompts(HookInput.UserPromptSubmitInput input) {
 *         log.info("User submitted prompt: {}", input.prompt());
 *     }
 * }
 * }</pre>
 *
 * @author Spring AI Community
 * @since 0.1.0
 * @see io.github.markpollack.claude.agent.sdk.hooks.HookCallback
 * @see io.github.markpollack.claude.agent.sdk.types.control.HookOutput
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserPromptSubmit {

	/**
	 * Timeout in seconds for hook execution. If the hook takes longer than this, the
	 * execution will be blocked with an error.
	 * @return timeout in seconds (default 60)
	 */
	int timeout() default 60;

}
