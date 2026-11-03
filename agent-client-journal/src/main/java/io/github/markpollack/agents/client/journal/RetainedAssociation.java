/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.journal.tck.ocsf.inventory.RetainedClaudeAcquisition;

/**
 * Join original Client identity to Journal's independently qualified retained sources.
 */
final class RetainedAssociation {

	private RetainedAssociation() {
	}

	static String pending(ExecutionDecision decision) {
		return DecisionEncoding
			.json(DecisionEncoding.object("codec", "journal-association/1", "executionId", decision.executionId(),
					"decisionId", decision.decisionId(), "revisionId", initial(decision), "priorRevisionId", null,
					"clientSessionId", decision.sessionId(), "connectionIds", decision.connectionId().stream().toList(),
					"providerObservation", DecisionEncoding.provider(decision.provider()), "acquisition", "PENDING",
					"continuation", null, "sources", List.of(), "missing",
					List.of(DecisionEncoding.object("origin", "generation:" + decision.connectionId().orElse("unknown"),
							"reason", "Sealed retained generation not yet attached"))));
	}

	static String initial(ExecutionDecision decision) {
		return decision.decisionId() + ":initial";
	}

	static NativeAssociation prepare(Path output, ExecutionDecision decision, Path generation) throws IOException {
		var manifest = new ObjectMapper().readTree(generation.toFile());
		if (!manifest.required("logicalSessionId").asText().equals(decision.sessionId())
				|| !decision.connectionId().equals(Optional.of(manifest.required("connectionId").asText())))
			throw new IllegalArgumentException("Generation does not belong to the original Client decision");
		var qualified = RetainedClaudeAcquisition.qualify(generation, decision.executionId(), output);
		return NativeAssociation.qualified(output, decision, decision.decisionId() + ":retained-final",
				initial(decision), qualified);
	}

}
