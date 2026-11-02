/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.nio.file.Path;
import io.github.markpollack.agents.model.AgentOptions;

/** Fixed retained-session configuration for the initial lifecycle API. */
public interface SessionSpec {

	SessionSpec workingDirectory(Path directory);

	SessionSpec options(AgentOptions options);

	SessionSpec binding(ConnectionBinding initial);

}
