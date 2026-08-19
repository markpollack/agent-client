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
 * Marks a method as a post-tool-use hook that is called after a tool has executed.
 *
 * <p>
 * The annotated method will be invoked after Claude executes the matched tool. The method
 * can inspect the tool result and perform logging, auditing, or other post-processing.
 * </p>
 *
 * <p>
 * Method signature requirements:
 * </p>
 * <ul>
 * <li>Parameter: {@link io.github.markpollack.claude.agent.sdk.types.control.HookInput}
 * or
 * {@link io.github.markpollack.claude.agent.sdk.types.control.HookInput.PostToolUseInput}</li>
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
 * public class AuditHooks {
 *

 *     &#64;PostToolUse
 *     public void auditAllTools(HookInput.PostToolUseInput input) {
 *         log.info("Tool {} completed with result: {}",
 *             input.toolName(), input.toolResult());
 *     }
 *
 *
&#64;PostToolUse(pattern = "Bash")
 *     public HookOutput checkBashResults(HookInput.PostToolUseInput input) {
 *         String result = input.toolResult();
 *         if (result != null && result.contains("error")) {
 *             log.warn("Bash command returned error: {}", result);
 *         }
 *         return HookOutput.allow();
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
public @interface PostToolUse {

	/**
	 * Regex pattern to match tool names.
	 * <p>
	 * Examples:
	 * <ul>
	 * <li>{@code "Bash"} - matches only Bash tool</li>
	 * <li>{@code "Bash|Write|Edit"} - matches Bash, Write, or Edit tools</li>
	 * <li>{@code ".*"} - matches all tools (same as empty string)</li>
	 * </ul>
	 * @return the tool pattern, or empty string to match all tools
	 */
	String pattern() default "";

	/**
	 * Timeout in seconds for hook execution. If the hook takes longer than this, the
	 * execution will be blocked with an error.
	 * @return timeout in seconds (default 60)
	 */
	int timeout() default 60;

}
