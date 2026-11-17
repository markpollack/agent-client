/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance;

import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The one verdict assertion the live Junie IT and the offline Junie row share. */
final class JunieVerdict {

	private JunieVerdict() {
	}

	/**
	 * Journal's verdict for a Junie 3813.7 session: ACCEPTED, with no defect, no
	 * unsupported item and no unresolved reason. The meaning of
	 * {@code modelUsage/*}{@code /time} is settled (client-measured wall-clock
	 * milliseconds, recorded as is), so any open reason fails.
	 */
	static void assertVerdict(JsonNode report) {
		String detail = report.path("acceptanceDetail").asText();
		var counts = java.util.regex.Pattern.compile("\"(DEFECTIVE|UNSUPPORTED|UNRESOLVED)\" : (\\d+)")
			.matcher(detail);
		while (counts.find())
			assertThat(Integer.parseInt(counts.group(2))).as(counts.group(1) + " in " + detail).isZero();
		assertThat(detail.lines()).as(detail)
			.noneMatch(line -> line.matches(".*\\] (DEFECTIVE|UNSUPPORTED|UNRESOLVED): .*"));
		assertThat(report.path("acceptance").asText()).as(detail).isEqualTo("ACCEPTED");
	}

}
