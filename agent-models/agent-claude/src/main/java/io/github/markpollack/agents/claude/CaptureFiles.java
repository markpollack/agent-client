/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

final class CaptureFiles {

	static final ObjectMapper JSON = new ObjectMapper();

	private CaptureFiles() {
	}

	static String hash(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				int size;
				while ((size = input.read(buffer)) != -1) {
					digest.update(buffer, 0, size);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static Map<String, Object> source(Path directory, Path file, String artifact, String role, String origin,
			String acquisition) throws IOException {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("artifact", artifact);
		source.put("role", role);
		source.put("path", directory.relativize(file).toString());
		source.put("origin", origin);
		source.put("acquisition", acquisition);
		source.put("state", "acquired");
		source.put("bytes", Files.size(file));
		source.put("sha256", hash(file));
		return source;
	}

	static Map<String, Object> missing(String artifact, String role, boolean required, String reason) {
		return Map.of("artifact", artifact, "role", role, "state", "missing", "required", required, "reason", reason);
	}

}
