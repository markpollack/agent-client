/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;

/** Detachment boundary; opaque mutable values are rejected, never silently retained. */
final class ResponseCopies {

	private ResponseCopies() {
	}

	static AgentClientResponse copy(AgentClientResponse response) {
		var source = response.agentResponse();
		var metadata = source.getMetadata();
		var generations = source.getResults().stream().map(ResponseCopies::generation).toList();
		var detached = new AgentResponseMetadata(metadata.getModel(), metadata.getDuration(), metadata.getSessionId(),
				map(metadata));
		return new AgentClientResponse(new AgentResponse(generations, detached), map(response.context()));
	}

	private static AgentGeneration generation(AgentGeneration value) {
		return new AgentGeneration(value.getOutput(), new AgentGenerationMetadata(value.getMetadata().getFinishReason(),
				map(value.getMetadata().getProviderFields())));
	}

	static Map<String, Object> map(Map<String, Object> values) {
		var result = new LinkedHashMap<String, Object>();
		values.forEach((key, value) -> result.put(key, value(value, 0)));
		return Collections.unmodifiableMap(result);
	}

	private static Object value(Object input, int depth) {
		if (depth > 32)
			throw new IllegalArgumentException("Snapshot nesting exceeds 32");
		if (input == null || scalar(input))
			return input;
		if (input instanceof List<?> list)
			return list.stream().map(v -> value(v, depth + 1)).toList();
		if (input instanceof Map<?, ?> map) {
			var result = new LinkedHashMap<String, Object>();
			map.forEach((key, v) -> result.put((String) key, value(v, depth + 1)));
			return Collections.unmodifiableMap(result);
		}
		throw new IllegalArgumentException("Unsupported snapshot value: " + input.getClass().getName());
	}

	private static final java.util.Set<Class<?>> SCALARS = java.util.Set.of(String.class, Boolean.class,
			Character.class, Integer.class, Long.class, Double.class, Float.class, Short.class, Byte.class,
			java.time.Instant.class, java.time.Duration.class, java.math.BigDecimal.class, java.math.BigInteger.class);

	private static boolean scalar(Object value) {
		return SCALARS.contains(value.getClass());
	}

}
