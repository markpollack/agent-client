/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.acp;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import io.github.markpollack.agents.model.AgentOptions;

/**
 * Everything about one ACP agent that {@link AcpAgentModel} cannot know.
 *
 * <h2>Why this is a profile and not a subclass</h2>
 *
 * <p>
 * The obvious shape for a second ACP provider is
 * {@code GrokAcpAgentModel extends AcpAgentModel}. It was rejected, and the two
 * implementations that exist say why: the things that differ between Junie and Grok do
 * not fall along an inheritance boundary. They are how the process is launched, whether
 * authentication is needed at all, where the durable trajectory lands, which parser reads
 * it, and which vendor {@code _meta} keys mean anything. Those are five independent axes;
 * expressed as subclasses they would need five hierarchies or one hierarchy that lies
 * about four of them.
 *
 * <p>
 * Every method below except {@link #providerKey()}, {@link #defaultCommand()} and
 * {@link #launchArgs} has a working default, so an ACP agent that needs nothing special
 * is a three-method profile.
 *
 * @author Mark Pollack
 * @since 0.30.0
 */
public interface AcpAgentProfile {

	/**
	 * The parity-suite provider key, for example {@code JUNIE} or {@code GROK}.
	 * @return the provider key
	 */
	String providerKey();

	/**
	 * The executable to launch when the caller names none.
	 * @return the default command
	 */
	String defaultCommand();

	/**
	 * The argv that puts this CLI into ACP mode for one run.
	 *
	 * <p>
	 * Measured, and not guessable from the protocol: Junie takes
	 * {@code --acp true --project
	 *
	<dir>
	 * }, Grok takes the {@code agent stdio} subcommand, and Gemini CLI takes a bare
	 * {@code --acp}. Nothing in ACP says how to start an ACP agent.
	 * @param workingDirectory the directory the run operates in
	 * @param options the merged options for this call
	 * @return arguments after the command itself
	 */
	List<String> launchArgs(Path workingDirectory, AgentOptions options);

	/**
	 * Environment for the launched process.
	 * @param options the merged options for this call
	 * @return environment variables to set
	 */
	default Map<String, String> environment(AgentOptions options) {
		return (options.getEnvironmentVariables() != null) ? options.getEnvironmentVariables() : Map.of();
	}

	/**
	 * Which advertised authentication method to invoke before opening a session, if any.
	 *
	 * <p>
	 * A third axis of divergence, measured 2026-08-26: Junie advertises no auth methods
	 * and needs none; Grok advertises {@code cached_token} and {@code grok.com} and
	 * authenticates itself from {@code ~/.grok/auth.json} without the client asking;
	 * Gemini CLI advertises four and rejects {@code session/new} outright until one has
	 * been chosen. Returning {@code null} — no explicit authentication — is right for two
	 * of the three.
	 * @param initialize the agent's initialize response, carrying its {@code authMethods}
	 * @return the method id to authenticate with, or null to skip authentication
	 */
	default String authMethodId(AcpSchema.InitializeResponse initialize) {
		return null;
	}

	/**
	 * How this agent's durable trajectory is addressed. See {@link AcpTrajectoryLocator}.
	 * @return the locator, never null
	 */
	default AcpTrajectoryLocator trajectoryLocator() {
		return AcpTrajectoryLocator.none();
	}

	/**
	 * The machine-authored name for a folded tool step.
	 *
	 * <p>
	 * ACP gives a tool call a human-readable {@code title} and an optional {@code kind},
	 * but no stable identifier for the tool itself. Where an agent puts one in
	 * {@code _meta}, only that agent's profile may read it: the schema is explicit that
	 * clients must not assume semantics for {@code _meta}, which makes interpreting it
	 * per-agent work by definition rather than something to standardise here.
	 * @param step the folded step
	 * @param meta the vendor meta attached to that call, possibly empty
	 * @return the name to record for this step
	 */
	default String toolName(AcpToolStep step, Map<String, Object> meta) {
		return step.title();
	}

	/**
	 * Turn a finished run into this agent's journal capture, published on
	 * {@code providerFields["phaseCapture"]}.
	 *
	 * <h2>The return type is {@code Object}, and that is the finding</h2>
	 *
	 * <p>
	 * {@code agent-journal} ships five capture types and five parsers with no interface
	 * in common, so there is no type to declare here. The honest signature is therefore
	 * {@code Object}, and it stays that way until {@code journal-core} grows the shared
	 * {@code PhaseCapture} the ACP tool-update shape already describes — portable tool
	 * id, status, content, locations, raw input and raw output. Narrowing this signature
	 * is the visible payoff of that change.
	 * @param run the folded run
	 * @return a capture object, or null when this agent has no trajectory to parse
	 */
	default Object capture(AcpRunRecord run) {
		return null;
	}

	/**
	 * Agent-specific fields to publish alongside the portable ones.
	 *
	 * <p>
	 * This is where an agent's own accounting surfaces without the shared model
	 * pretending to understand it. Grok, for instance, returns its entire token and cost
	 * vector in the prompt response {@code _meta}; Junie returns none there and reports
	 * cost only in its trajectory.
	 * @param run the folded run
	 * @return extra provider fields, possibly empty
	 */
	default Map<String, Object> providerFields(AcpRunRecord run) {
		return Map.of();
	}

	/**
	 * The model label to report when the caller named no model.
	 * @return a default label
	 */
	default String defaultModelLabel() {
		return providerKey().toLowerCase(Locale.ROOT) + "-default";
	}

}
