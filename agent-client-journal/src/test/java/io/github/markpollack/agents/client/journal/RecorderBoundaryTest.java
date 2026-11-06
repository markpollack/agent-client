/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.client.journal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.markpollack.agents.client.journal.archcontrol.ProviderDependentControl;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorder depends on no provider. Its production classes may use only Client core
 * and model, Journal's writer and TCK, Jackson and the JDK: an allowlist, so a provider
 * added later is excluded without editing this rule. Its POM declares no provider
 * dependency outside test scope, which a bytecode rule cannot see.
 */
class RecorderBoundaryTest {

	private static final String RECORDER = "io.github.markpollack.agents.client.journal..";

	private static final String[] ALLOWED = { "java..", "io.github.markpollack.agents.client..",
			"io.github.markpollack.agents.model..", "io.github.markpollack.journal.ocsf.writer..",
			"io.github.markpollack.journal.tck..", "com.fasterxml.jackson.." };

	/** Non-test dependencies the recorder module may declare. */
	private static final Set<String> ALLOWED_ARTIFACTS = Set.of("agent-client-core", "journal-ocsf-writer",
			"journal-capture-tck", "jackson-databind");

	/** This module's compiled main classes only; never test classes. */
	private static final JavaClasses PRODUCTION = new ClassFileImporter().importPath(Path.of("target", "classes"));

	private static final ArchRule BOUNDARY = classes().that()
		.resideInAPackage(RECORDER)
		.should()
		.onlyDependOnClassesThat()
		.resideInAnyPackage(ALLOWED)
		.because("the recorder serves every provider through one generic path");

	@Test
	void selectsTheRecorderClasses() {
		var selected = PRODUCTION.stream()
			.filter(c -> c.getPackageName().startsWith("io.github.markpollack.agents.client.journal"))
			.map(JavaClass::getSimpleName)
			.toList();
		assertThat(selected).contains("JournalExecutionRecorder", "DecisionEncoding");
		assertThat(PRODUCTION.stream().map(JavaClass::getName)).noneMatch(n -> n.contains("archcontrol"))
			.noneMatch(n -> n.endsWith("Test"));
	}

	@Test
	void recorderDependsOnNoProviderPackage() {
		BOUNDARY.check(PRODUCTION);
	}

	@Test
	void ruleReportsAProviderDependency() {
		var control = new ClassFileImporter().importClasses(ProviderDependentControl.class);
		var result = BOUNDARY.evaluate(control);
		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails())
			.anyMatch(d -> d.contains("io.github.markpollack.agents.claude.ClaudeAcquisition"));
	}

	@Test
	void pomDeclaresNoProviderDependency() throws Exception {
		var factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		var document = factory.newDocumentBuilder().parse(Files.newInputStream(Path.of("pom.xml")));
		var dependencies = document.getElementsByTagName("dependency");
		List<String> declared = new ArrayList<>();
		for (int i = 0; i < dependencies.getLength(); i++) {
			var dependency = (Element) dependencies.item(i);
			var scope = dependency.getElementsByTagName("scope");
			if (scope.getLength() == 1 && "test".equals(scope.item(0).getTextContent().trim()))
				continue;
			declared.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent().trim());
		}
		assertThat(declared).as("non-test dependencies of agent-client-journal")
			.isNotEmpty()
			.allMatch(ALLOWED_ARTIFACTS::contains);
	}

}
