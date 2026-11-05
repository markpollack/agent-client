/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.util.Iterator;
import java.util.function.Consumer;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;

/** Forwards each message to a listener before yielding it to the consuming parser. */
final class TeeingIterator implements Iterator<ParsedMessage> {

	private final Iterator<ParsedMessage> delegate;

	private final Consumer<ParsedMessage> listener;

	TeeingIterator(Iterator<ParsedMessage> delegate, Consumer<ParsedMessage> listener) {
		this.delegate = delegate;
		this.listener = listener;
	}

	@Override
	public boolean hasNext() {
		return delegate.hasNext();
	}

	@Override
	public ParsedMessage next() {
		ParsedMessage message = delegate.next();
		listener.accept(message);
		return message;
	}

}
