/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.junie;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.markpollack.agents.junie.JunieAcpProfile;
import io.github.markpollack.agents.model.AgentOptions;

/**
 * Junie's ACP profile with a fixed launch environment. Junie 3813.7 writes the
 * environment it was launched with into {@code events.jsonl}, which this provider seals
 * byte for byte, so Junie never inherits the JVM's environment here: it gets the
 * allow-listed process variables below plus the variables the options name, and nothing
 * else.
 */
final class JunieLaunchProfile extends JunieAcpProfile {

	/** Process plumbing Junie needs to start and find its home; none carries a secret. */
	static final Set<String> INHERITED = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL",
			"LC_CTYPE", "TERM", "TMPDIR", "TZ", "JAVA_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
			"XDG_STATE_HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");

	private final Map<String, String> parent;

	JunieLaunchProfile(Path sessionsDirectory, Map<String, String> parent) {
		super(sessionsDirectory);
		this.parent = parent;
	}

	@Override
	public boolean inheritEnvironment() {
		return false;
	}

	@Override
	public Map<String, String> environment(AgentOptions options) {
		var environment = new LinkedHashMap<String, String>();
		new TreeMap<>(this.parent).forEach((name, value) -> {
			if (INHERITED.contains(name))
				environment.put(name, value);
		});
		if (options != null && options.getEnvironmentVariables() != null)
			environment.putAll(options.getEnvironmentVariables());
		return environment;
	}

	/**
	 * Whether a variable name looks like it carries a credential. Socket and display
	 * plumbing whose names mention auth are not credentials.
	 */
	static boolean credentialShaped(String name) {
		String upper = name.toUpperCase(Locale.ROOT);
		if (upper.equals("SSH_AUTH_SOCK") || upper.equals("XAUTHORITY"))
			return false;
		for (String marker : new String[] { "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH",
				"COOKIE", "SESSION_ID" })
			if (upper.contains(marker))
				return true;
		return false;
	}

}
