/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncWireContractTest {

	@Test
	void configuredTimeoutDescriptorHasExactStableWireMeaning() throws Exception {
		var clock = new AsyncLifecycleTest.Manual();
		var execution = clock.client(new AsyncLifecycleTest.Provider())
			.goal("never dispatched")
			.executionTimeout(Duration.ofNanos(5))
			.runAsync();
		clock.tick(5);
		var failure = ((ExecutionOutcome.Failed) execution.decision().orElseThrow().outcome()).failure();
		var mapper = new ObjectMapper();
		var root = mapper.createObjectNode();
		var detail = root.putObject("failure");
		var causes = detail.putArray("causes");
		failure.causes().forEach(causes::add);
		detail.put("code", failure.code().wireName());
		detail.put("exceptionClass", failure.exceptionClass());
		detail.put("message", failure.message());
		root.put("kind", "FAILED");
		root.putNull("reason");
		assertThat(mapper.writeValueAsString(root)).isEqualTo(
				"{\"failure\":{\"causes\":[],\"code\":\"TIMEOUT\",\"exceptionClass\":\"java.util.concurrent.TimeoutException\",\"message\":\"Execution deadline exceeded\"},\"kind\":\"FAILED\",\"reason\":null}");
		assertThat(execution.isCancelled()).isFalse();
		assertThat(execution.inspect().providerTurnDispatched()).isFalse();
	}

}
