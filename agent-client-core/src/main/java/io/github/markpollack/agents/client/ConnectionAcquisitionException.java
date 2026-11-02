/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client;

/**
 * Failed acquisition retaining a partially acquired connection for Client-owned cleanup.
 */
public final class ConnectionAcquisitionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient AgentExecutionProvider.Connection connection;

	public ConnectionAcquisitionException(String message, Throwable cause,
			AgentExecutionProvider.Connection connection) {
		super(message, cause);
		this.connection = java.util.Objects.requireNonNull(connection);
	}

	public AgentExecutionProvider.Connection connection() {
		return this.connection;
	}

}
