/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client.autoconfigure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A starter without this module gives its users a provider agent but no
 * {@code AgentClient.Builder}. Every starter in the reactor must declare it directly.
 */
class StarterDependencyTest {

	private static final Path AGENT_STARTERS = Path.of("..", "agent-starters");

	private static final String AUTOCONFIGURE = "agent-client-spring-boot-autoconfigure";

	@Test
	void everyStarterDependsOnTheAutoconfigureModule() throws Exception {
		List<Path> starters;
		try (Stream<Path> modules = Files.list(AGENT_STARTERS)) {
			starters = modules.map(module -> module.resolve("pom.xml")).filter(Files::isRegularFile).sorted().toList();
		}
		assertThat(starters).as("starters found under %s", AGENT_STARTERS.toAbsolutePath())
			.hasSizeGreaterThanOrEqualTo(9);

		List<String> missing = new ArrayList<>();
		for (Path pom : starters) {
			if (!directDependencies(pom).contains(AUTOCONFIGURE)) {
				missing.add(pom.getParent().getFileName().toString());
			}
		}
		assertThat(missing).as("starters without a direct %s dependency", AUTOCONFIGURE).isEmpty();
	}

	private static List<String> directDependencies(Path pom) throws Exception {
		Element project = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(pom.toFile())
			.getDocumentElement();
		List<String> artifactIds = new ArrayList<>();
		Element dependencies = child(project, "dependencies");
		if (dependencies == null) {
			return artifactIds;
		}
		NodeList children = dependencies.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i) instanceof Element dependency && "dependency".equals(dependency.getTagName())) {
				Element artifactId = child(dependency, "artifactId");
				if (artifactId != null) {
					artifactIds.add(artifactId.getTextContent().strip());
				}
			}
		}
		return artifactIds;
	}

	private static Element child(Element parent, String name) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && name.equals(element.getTagName())) {
				return element;
			}
		}
		return null;
	}

}
