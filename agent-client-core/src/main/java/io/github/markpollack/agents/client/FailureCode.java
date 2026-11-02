/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.Objects;

/**
 * Client-owned categories. Explicit wire names are stable independently of Java names.
 */
public enum FailureCode {

	TIMEOUT("TIMEOUT"), SETUP_TIMEOUT("SETUP_TIMEOUT"), SCHEDULING("SCHEDULING"), SETUP_FAILED("SETUP_FAILED"),
	PROVIDER_TRANSPORT("PROVIDER_TRANSPORT"), PROVIDER_PROTOCOL("PROVIDER_PROTOCOL"), ADVISOR("ADVISOR"),
	RESULT_COPY("RESULT_COPY"), CLIENT_PROCESSING("CLIENT_PROCESSING"), PERSISTENCE_FAILED("PERSISTENCE_FAILED"),
	RECORDING_FAILED("RECORDING_FAILED"), RECORDING_TIMEOUT("RECORDING_TIMEOUT"),
	RECORDING_CONFLICT("RECORDING_CONFLICT");

	private final String wireName;

	FailureCode(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static FailureCode fromWireName(String value) {
		Objects.requireNonNull(value, "value");
		for (FailureCode code : values()) {
			if (code.wireName.equals(value))
				return code;
		}
		throw new IllegalArgumentException("Unknown Client failure code: " + value);
	}

}
