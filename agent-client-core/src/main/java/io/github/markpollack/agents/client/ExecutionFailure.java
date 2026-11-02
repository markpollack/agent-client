/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

record ExecutionFailure(FailureInfo info, Throwable cause) {
	static ExecutionFailure of(FailureCode code, Throwable failure) {
		var causes = new ArrayList<String>();
		var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		Throwable next = failure;
		while (next != null && seen.add(next) && causes.size() < 16) {
			causes.add(next.getClass().getName() + ": " + message(next));
			try {
				next = next.getCause();
			}
			catch (RuntimeException ex) {
				causes.add("Cause unavailable: " + ex.getClass().getName());
				break;
			}
		}
		return new ExecutionFailure(new FailureInfo(code, failure.getClass().getName(), message(failure), causes),
				failure);
	}

	private static String message(Throwable failure) {
		try {
			return java.util.Objects.toString(failure.getMessage(), "");
		}
		catch (RuntimeException ex) {
			return "Message unavailable: " + ex.getClass().getName();
		}
	}
}
