/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.micronaut;

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Micronaut platform BOM manages Jackson 2.21.6, below the 2.22.3 floor. This module
 * imports the Jackson BOM first, so its build and tests resolve the floor or later.
 */
class JacksonVersionTest {

	@Test
	void jackson2IsAtLeastTheFloor() {
		assertThat(number(com.fasterxml.jackson.core.json.PackageVersion.VERSION)).isGreaterThanOrEqualTo(22203);
		assertThat(number(com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION)).isGreaterThanOrEqualTo(22203);
	}

	/**
	 * 2.22.3 as 22203: the numbers only, since {@link Version#compareTo} also compares
	 * ids.
	 */
	private static int number(Version version) {
		return version.getMajorVersion() * 10000 + version.getMinorVersion() * 100 + version.getPatchLevel();
	}

}
