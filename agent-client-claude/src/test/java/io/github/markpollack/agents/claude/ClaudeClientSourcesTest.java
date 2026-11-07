/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.charset.StandardCharsets;

import io.github.markpollack.claude.agent.sdk.transport.TransportByteObserver.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one-shot source buffer keeps everything up to its limit and names what it drops.
 */
class ClaudeClientSourcesTest {

	@Test
	void outputPastTheLimitIsCountedAndNamedNeverSilentlyLost() {
		var sources = new ClaudeClientSources(new ClaudeCaptureLimit(16, 1));
		sources.observe(Stream.STDOUT, "0123456789".getBytes(StandardCharsets.UTF_8));
		sources.observe(Stream.STDOUT, "0123456789".getBytes(StandardCharsets.UTF_8));
		sources.observe(Stream.STDERR, "abc".getBytes(StandardCharsets.UTF_8));
		assertThat(sources.droppedFailure()).isEqualTo("Capture limit of 16 bytes reached; dropped 10 bytes");
	}

	@Test
	void outputWithinTheLimitDropsNothing() {
		var sources = new ClaudeClientSources();
		sources.observe(Stream.STDOUT, new byte[5 * 1024 * 1024]);
		assertThat(sources.droppedFailure()).isNull();
	}

	@Test
	void aLimitMustBePositiveAndFitOneArray() {
		assertThatThrownBy(() -> new ClaudeCaptureLimit(0, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaudeCaptureLimit(Integer.MAX_VALUE, 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
