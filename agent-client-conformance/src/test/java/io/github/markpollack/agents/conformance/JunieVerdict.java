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
	 * Journal's verdict for a Junie 3813.7 session today: INCOMPLETE with no defect, and
	 * the only open reason the unresolved meaning of {@code modelUsage/*}{@code /time} in
	 * the frozen session-events specification. Any defect, unsupported item or other
	 * unresolved reason fails. When the Owner settles that meaning, Journal's verdict becomes
	 * ACCEPTED and this tightens to it.
	 */
	static void assertVerdict(JsonNode report) {
		String detail = report.path("acceptanceDetail").asText();
		var counts = java.util.regex.Pattern.compile("\"(DEFECTIVE|UNSUPPORTED)\" : (\\d+)").matcher(detail);
		while (counts.find())
			assertThat(Integer.parseInt(counts.group(2))).as(counts.group(1) + " in " + detail).isZero();
		var reasons = detail.lines().filter(line -> line.matches(".*\\] (DEFECTIVE|UNSUPPORTED|UNRESOLVED): .*")).toList();
		assertThat(reasons).as(detail)
			.noneMatch(line -> line.contains("] DEFECTIVE: ") || line.contains("] UNSUPPORTED: "));
		assertThat(reasons).as(detail)
			.filteredOn(line -> line.contains("] UNRESOLVED: "))
			.isNotEmpty()
			.allMatch(line -> line.contains("/modelUsage/*/time: "));
		assertThat(reasons).anyMatch(line -> line.contains("/modelUsage/*/time: "));
		assertThat(report.path("acceptance").asText()).as(detail).startsWith("INCOMPLETE");
	}

}
