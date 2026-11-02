/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public interface AgentSession extends AutoCloseable {

	String id();

	Optional<ProviderReference> providerReference();

	SessionCapabilities capabilities();

	AgentClient.AgentClientRequestSpec goal(String prompt);

	SessionSnapshot inspect();

	@Override
	void close();

}
