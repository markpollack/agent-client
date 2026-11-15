/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.quarkus.deployment;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.github.markpollack.agents.client.quarkus.AgentClientBuilderProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Registers the Agent Client beans: the {@code AgentClient.Builder} producer, and the
 * producer of each lifecycle provider whose module is on the application's classpath and
 * whose build-time property {@code agent-client.lifecycle.<provider>.enabled} is
 * {@code true}.
 */
class AgentClientProcessor {

	private static final String FEATURE = "agent-client";

	private static final String PRODUCERS = "io.github.markpollack.agents.client.quarkus.";

	/**
	 * Each lifecycle producer, keyed by the provider class it needs at runtime. Both are
	 * names, so a producer whose provider is absent is never loaded.
	 */
	private static final Map<String, Lifecycle> LIFECYCLE_PRODUCERS = Map.of("claude",
			new Lifecycle(
					"io.github.markpollack.agents.claude.ClaudeClientProvider", PRODUCERS + "ClaudeLifecycleProducer"),
			"codex",
			new Lifecycle("io.github.markpollack.agents.codex.CodexClientProvider",
					PRODUCERS + "CodexLifecycleProducer"),
			"antigravity", new Lifecycle("io.github.markpollack.agents.antigravity.AntigravityClientProvider",
					PRODUCERS + "AntigravityLifecycleProducer"));

	/** A lifecycle provider class and the producer that creates it. */
	private record Lifecycle(String provider, String producer) {
	}

	@BuildStep
	FeatureBuildItem feature() {
		return new FeatureBuildItem(FEATURE);
	}

	@BuildStep
	void beans(BuildProducer<AdditionalBeanBuildItem> beans) {
		beans.produce(AdditionalBeanBuildItem.unremovableOf(AgentClientBuilderProducer.class));
		Config config = ConfigProvider.getConfig();
		for (String producer : lifecycleProducers(key -> config.getOptionalValue(key, Boolean.class).orElse(false),
				QuarkusClassLoader::isClassPresentAtRuntime)) {
			beans.produce(AdditionalBeanBuildItem.builder().addBeanClasses(producer).setUnremovable().build());
		}
	}

	/**
	 * The lifecycle producers to register: each provider that is enabled and on the
	 * classpath.
	 * @param enabled whether a build-time property is true
	 * @param present whether a class is on the application's runtime classpath
	 * @return the producer class names, in provider-name order
	 */
	static List<String> lifecycleProducers(Predicate<String> enabled, Predicate<String> present) {
		return LIFECYCLE_PRODUCERS.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.filter(entry -> enabled.test("agent-client.lifecycle." + entry.getKey() + ".enabled"))
			.filter(entry -> present.test(entry.getValue().provider()))
			.map(entry -> entry.getValue().producer())
			.toList();
	}

}
