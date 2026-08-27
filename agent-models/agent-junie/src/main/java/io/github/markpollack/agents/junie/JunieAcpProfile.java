/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.junie;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.markpollack.agents.acp.AcpAgentProfile;
import io.github.markpollack.agents.acp.AcpRunRecord;
import io.github.markpollack.agents.acp.AcpToolStep;
import io.github.markpollack.agents.acp.AcpTrajectoryLocator;
import io.github.markpollack.agents.acp.AcpTrajectoryLocators;
import io.github.markpollack.agents.model.AgentOptions;
import io.github.markpollack.journal.junie.JunieSessionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Junie over ACP: the baseline the generic model was measured against.
 *
 * <p>
 * Junie was the first ACP provider here, and its adapter deliberately kept every piece of
 * protocol knowledge in one class rather than generalising from a single example. This
 * profile is what remained once a second ACP agent showed which of that was protocol and
 * which was Junie.
 *
 * <h2>Where Junie and Grok genuinely disagree</h2>
 *
 * <ul>
 * <li><strong>Trajectory address.</strong> Junie's session directory is named with
 * exactly the id from {@code session/new}, so a session id alone resolves it. Grok
 * partitions by working directory first, so it does not.</li>
 * <li><strong>Tool identity.</strong> Grok publishes a stable machine name in its
 * {@code _meta}; Junie publishes none at all, splitting identity between an event kind
 * and a model-authored prose label. Junie's ACP {@code kind} is therefore the identity
 * here, which is the opposite of the rule that fits Grok.</li>
 * <li><strong>Where cost lives.</strong> Grok returns its whole usage vector on the
 * prompt response; Junie returns nothing there and reports cost only inside its
 * trajectory, which is why this profile needs a parser and Grok's does not.</li>
 * </ul>
 *
 * <h2>The trajectory is secret-bearing</h2>
 *
 * <p>
 * Junie writes the launching process's entire environment into {@code events.jsonl},
 * unredacted and repeatedly — one observed run carried 103 variables including live API
 * keys. Only the path is published, the parser discards that event, and no archival step
 * exists. Anything that later copies or normalises this file must redact first.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public class JunieAcpProfile implements AcpAgentProfile {

	private static final Logger logger = LoggerFactory.getLogger(JunieAcpProfile.class);

	private final Path sessionsDirectory;

	public JunieAcpProfile() {
		this(defaultSessionsDirectory());
	}

	public JunieAcpProfile(Path sessionsDirectory) {
		this.sessionsDirectory = (sessionsDirectory != null) ? sessionsDirectory : defaultSessionsDirectory();
	}

	/**
	 * Matches {@code Provider.JUNIE} in the parity TCK. Spelled as a literal so that a
	 * test-scope compatibility kit does not become a runtime dependency of the adapter.
	 */
	@Override
	public String providerKey() {
		return "JUNIE";
	}

	@Override
	public String defaultCommand() {
		return "junie";
	}

	@Override
	public List<String> launchArgs(Path workingDirectory, AgentOptions options) {
		return buildLaunchArgs(workingDirectory, options);
	}

	/**
	 * The {@code junie} command line for one run.
	 *
	 * <p>
	 * Static and package-private so the passthrough contract is directly testable without
	 * a CLI: neutral options become Junie's own flags, and every extra becomes
	 * {@code --key value}, with a {@code true} boolean becoming a bare flag and
	 * {@code false} omitted entirely.
	 * @param workingDirectory the project directory for this run
	 * @param options the merged options
	 * @return arguments after {@code junie}
	 */
	static List<String> buildLaunchArgs(Path workingDirectory, AgentOptions options) {
		List<String> args = new ArrayList<>(List.of("--acp", "true", "--project", workingDirectory.toString()));
		if (options == null) {
			return args;
		}
		if (options.getModel() != null) {
			args.add("--model");
			args.add(options.getModel());
		}
		if (options.getEffort() != null) {
			args.add("--effort");
			args.add(options.getEffort());
		}
		for (Map.Entry<String, Object> extra : options.getExtras().entrySet()) {
			Object value = extra.getValue();
			if (value instanceof Boolean flag) {
				if (flag) {
					args.add("--" + extra.getKey());
				}
				continue;
			}
			if (value != null) {
				args.add("--" + extra.getKey());
				args.add(String.valueOf(value));
			}
		}
		return args;
	}

	/**
	 * {@code ~/.junie/sessions/<sessionId>/events.jsonl}.
	 *
	 * <p>
	 * Junie names each session directory with exactly the id returned by
	 * {@code session/new} — verified against a live 26.8.24 run by snapshotting the
	 * sessions directory around the call. That correspondence is what makes this a lookup
	 * rather than a search over recently modified directories, and it is worth
	 * re-checking whenever the session id format changes.
	 */
	@Override
	public AcpTrajectoryLocator trajectoryLocator() {
		return AcpTrajectoryLocators.bySessionId(this.sessionsDirectory, "events.jsonl").existingOnly();
	}

	/**
	 * Junie's ACP {@code kind}, not its title.
	 *
	 * <p>
	 * Junie has no raw tool name: identity is split between a machine-authored event kind
	 * and a model-authored prose label. The kind is closed, stable and discriminating;
	 * the prose has unbounded cardinality, which yields a useless transition matrix — the
	 * mirror image of Codex collapsing every tool to one symbol.
	 */
	@Override
	public String toolName(AcpToolStep step, Map<String, Object> meta) {
		return (step.kind() != null) ? step.kind() : step.title();
	}

	/**
	 * Republish the trajectory path under Junie's own key.
	 *
	 * <p>
	 * {@code AcpAgentModel} publishes a provider-neutral {@code trajectoryPath}; this
	 * keeps {@code eventsPath} alongside it because that is the name Junie consumers and
	 * the Junie integration tests already use.
	 */
	@Override
	public Map<String, Object> providerFields(AcpRunRecord run) {
		return (run.trajectory() != null) ? Map.of("eventsPath", run.trajectory().toString()) : Map.of();
	}

	@Override
	public Object capture(AcpRunRecord run) {
		try {
			return JunieSessionParser.parse(run.trajectory(), "junie-acp", run.promptText());
		}
		catch (IOException ex) {
			// A trajectory that will not parse must not fail a run that succeeded.
			logger.warn("Junie phase capture failed for session {}: {}", run.sessionId(), ex.getMessage());
			return null;
		}
	}

	@Override
	public String defaultModelLabel() {
		return "junie-default";
	}

	private static Path defaultSessionsDirectory() {
		return Paths.get(System.getProperty("user.home"), ".junie", "sessions");
	}

}
