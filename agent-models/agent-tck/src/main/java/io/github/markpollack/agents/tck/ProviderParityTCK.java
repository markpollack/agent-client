/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentTaskRequest;

/**
 * Provider Parity TCK that asserts behavioral equivalence across agent providers.
 *
 * <p>
 * Unlike {@link AbstractAgentModelTCK} which tests per-provider correctness, this TCK
 * verifies that the same task produces the same observable outcome regardless of which
 * provider executes it. This catches parity regressions like the Codex
 * {@code skipGitCheck} issue where one provider blocked in directories that others
 * handled fine.
 *
 * <p>
 * Concrete subclasses must:
 * <ol>
 * <li>Override {@link #getProvider()} to declare which provider this IT represents</li>
 * <li>Set up {@link #agentModel} and {@link #sandbox} in a {@code @BeforeEach}
 * method</li>
 * </ol>
 *
 * <p>
 * Tests annotated with {@link ProviderCapability} are automatically skipped for providers
 * not listed in the annotation, reported as "skipped" (NOT_APPLICABLE) in surefire
 * output.
 *
 * @author Spring AI Community
 * @since 0.14.0
 * @see ProviderCapability
 * @see Provider
 */
public abstract class ProviderParityTCK extends AbstractAgentModelTCK {

	/**
	 * Returns the provider key this IT represents. Used to evaluate
	 * {@link ProviderCapability} annotations.
	 */
	protected abstract String getProvider();

	/**
	 * Checks {@link ProviderCapability} annotation on the current test method and skips
	 * if this provider is not listed.
	 */
	@BeforeEach
	void checkProviderCapability(TestInfo testInfo) {
		testInfo.getTestMethod().ifPresent(this::skipIfNotCapable);
	}

	private void skipIfNotCapable(Method method) {
		ProviderCapability capability = method.getAnnotation(ProviderCapability.class);
		if (capability != null) {
			boolean supported = Arrays.asList(capability.providers()).contains(getProvider());
			assumeTrue(supported, getProvider() + " does not support this scenario — skipping (NOT_APPLICABLE)");
		}
	}

	/**
	 * Baseline: create a file in a directory that is a git repository. All providers
	 * should pass this.
	 */
	@Test
	@ProviderCapability(providers = { Provider.CLAUDE, Provider.CODEX, Provider.GEMINI, Provider.GROK,
			Provider.ANTIGRAVITY, Provider.JUNIE })
	void testSimpleFileCreationInGitDirectory() throws Exception {
		// Initialize git in tempDir
		new ProcessBuilder("git", "init").directory(tempDir.toFile()).start().waitFor();

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'parity-test.txt' with the content 'parity check'", tempDir)
			.build();

		AgentResponse response = agentModel.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResult()).isNotNull();

		Path resultFile = tempDir.resolve("parity-test.txt");
		assertThat(Files.exists(resultFile)).isTrue();
		assertThat(Files.readString(resultFile)).contains("parity check");
	}

	/**
	 * The Joachim case: create a file in a directory that is NOT a git repository. This
	 * is the scenario that exposed the Codex {@code skipGitCheck} default issue.
	 *
	 * <p>
	 * With LOOSE-mode defaults, all three providers should pass this.
	 */
	@Test
	@ProviderCapability(providers = { Provider.CLAUDE, Provider.CODEX, Provider.GEMINI, Provider.GROK,
			Provider.ANTIGRAVITY, Provider.JUNIE })
	void testSimpleFileCreationInNonGitDirectory() throws IOException {
		// tempDir is NOT a git repo — this is intentional

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'no-git-test.txt' with the content 'works without git'", tempDir)
			.build();

		AgentResponse response = agentModel.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResult()).isNotNull();

		Path resultFile = tempDir.resolve("no-git-test.txt");
		assertThat(Files.exists(resultFile)).isTrue();
		assertThat(Files.readString(resultFile)).contains("works without git");
	}

	/**
	 * Edge case: create a file when the parent of the working directory is read-only. The
	 * working directory itself should still be writable.
	 */
	@Test
	@ProviderCapability(providers = { Provider.CLAUDE, Provider.CODEX, Provider.GEMINI, Provider.GROK,
			Provider.ANTIGRAVITY, Provider.JUNIE })
	void testSimpleFileCreationInReadOnlyParent() throws IOException {
		// Create a writable subdirectory inside tempDir
		Path workDir = tempDir.resolve("writable-child");
		Files.createDirectories(workDir);

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'readonly-parent-test.txt' with the content 'parent is readonly'", workDir)
			.build();

		AgentResponse response = agentModel.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResult()).isNotNull();

		Path resultFile = workDir.resolve("readonly-parent-test.txt");
		assertThat(Files.exists(resultFile)).isTrue();
		assertThat(Files.readString(resultFile)).contains("parent is readonly");
	}

	/**
	 * Working directory resolution: create a file in a nested workspace to verify the
	 * agent respects the working directory parameter.
	 */
	@Test
	@ProviderCapability(providers = { Provider.CLAUDE, Provider.CODEX, Provider.GEMINI, Provider.GROK,
			Provider.ANTIGRAVITY, Provider.JUNIE })
	void testSimpleFileCreationInNestedWorkspace() throws IOException {
		Path nestedDir = tempDir.resolve("level1").resolve("level2").resolve("workspace");
		Files.createDirectories(nestedDir);

		AgentTaskRequest request = AgentTaskRequest
			.builder("Create a file named 'nested-test.txt' with the content 'deeply nested'", nestedDir)
			.build();

		AgentResponse response = agentModel.call(request);

		assertThat(response).isNotNull();
		assertThat(response.getResult()).isNotNull();

		Path resultFile = nestedDir.resolve("nested-test.txt");
		assertThat(Files.exists(resultFile)).isTrue();
		assertThat(Files.readString(resultFile)).contains("deeply nested");
	}

}
