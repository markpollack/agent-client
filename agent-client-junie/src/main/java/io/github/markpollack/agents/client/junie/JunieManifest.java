/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.ExecutionDecision;

/**
 * The sealed manifest, in the envelope Agent Journal's Junie contract names
 * ({@code docs/junie-acquisition.md} in agent-journal): kind
 * {@code agent-journal/acquired-execution} 0.1, profile
 * {@code junie-<version>-acp-session-files}.
 */
final class JunieManifest {

	static final String UNKNOWN_VERSION = "UNKNOWN";

	private JunieManifest() {
	}

	static String profile(String version) {
		return "junie-" + (version == null ? UNKNOWN_VERSION : version) + "-acp-session-files";
	}

	static String profileId(String version) {
		return "junie/" + (version == null ? UNKNOWN_VERSION : version) + "/acp+summary+session-events";
	}

	static byte[] write(JunieAcquisition a, ExecutionDecision decision) throws IOException {
		String execution = a.executionId();
		var root = object("kind", "agent-journal/acquired-execution", "version", "0.1", "profile",
				profile(a.version()), "profile_id", profileId(a.version()), "acquisition_uid", execution, "complete",
				a.complete(), "capture_errors", a.errors());
		root.put("run",
				object("run_uid", execution, "invocation_index", 0, "capture_start", a.captureStart().toString(), "cli",
						object("name", "Junie", "vendor_name", "JetBrains", "version",
								a.version() == null ? UNKNOWN_VERSION : a.version()),
						"launch_settings",
						object("route", "acp_stdio", "working_directory", a.workingDirectory().toString()),
						"session_uid", a.acpSessionId()));
		root.put("process", object("argv", a.argv(), "cwd", a.workingDirectory().toString(), "client_session_uid",
				decision.sessionId(), "connection_uid", a.connectionId(), "acp_session_id", a.acpSessionId(),
				"acp_stop_reason", a.stopReason(), "fresh_session", a.freshSession(), "native_sessions_directory",
				a.nativeSessionsDirectory().toString(), "native_sessions_new", a.nativeSessionsNew(),
				"observed_resources_terminated", a.terminated(), "sealed", true));
		var sources = new ArrayList<Map<String, Object>>();
		for (var source : a.sources())
			sources.add(source(source, execution, a.acpSessionId()));
		root.put("sources", sources);
		root.put("turns", List.of(object("invocation_uid", execution, "decision_uid", decision.decisionId(),
				"native_task_uid", a.nativeTaskId())));
		return new ObjectMapper().writeValueAsBytes(root);
	}

	private static Map<String, Object> source(JunieAcquisition.Source s, String execution, String session) {
		var result = object("role", s.role(), "artifact", s.artifact(), "path", s.artifact(), "profile", s.profile(),
				"route", "native_session_file", "origin", s.origin().toString(), "owner_session_uid", session,
				"acquisition", execution, "required", true, "archive_only", false, "state",
				s.state(), "bytes", s.bytes(), "observed", s.observed(), "dropped",
				s.observed() - s.bytes(), "end", s.acquired() && s.observed() == s.bytes() ? "EOF" : "UNKNOWN",
				"framing", s.framing(), "records", s.records());
		result.put("sha256", s.sha256());
		return result;
	}

	/**
	 * The launch command as recorded: the value after an option that names a key,
	 * token, secret or password, or after {@code -a}/{@code --auth}, is replaced, so a
	 * credential passed on the command line never reaches the evidence.
	 */
	static List<String> redact(List<String> command) {
		var out = new ArrayList<String>(command.size());
		boolean secret = false;
		for (String arg : command) {
			if (secret) {
				out.add("<redacted>");
				secret = false;
				continue;
			}
			int eq = arg.indexOf('=');
			String option = eq > 0 ? arg.substring(0, eq) : arg;
			boolean names = option.startsWith("-") && sensitive(option);
			if (names && eq > 0)
				out.add(option + "=<redacted>");
			else {
				out.add(arg);
				secret = names;
			}
		}
		return List.copyOf(out);
	}

	private static boolean sensitive(String option) {
		String name = option.toLowerCase(Locale.ROOT);
		return name.equals("-a") || name.equals("--auth") || name.contains("key") || name.contains("token")
				|| name.contains("secret") || name.contains("password");
	}

	private static Map<String, Object> object(Object... pairs) {
		var result = new LinkedHashMap<String, Object>();
		for (int i = 0; i < pairs.length; i += 2)
			result.put((String) pairs[i], pairs[i + 1]);
		return result;
	}

}
