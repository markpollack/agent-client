/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.qwencode;

import java.util.List;

import com.alibaba.qwen.code.cli.transport.TransportOptions;

/**
 * Functional interface wrapping QwenCodeCli.simpleQuery() to enable unit testing without
 * static mocking.
 *
 * @author Spring AI Community
 * @since 0.12.0
 */
@FunctionalInterface
interface QwenCodeQueryFunction {

	List<String> query(String prompt, TransportOptions options);

}
