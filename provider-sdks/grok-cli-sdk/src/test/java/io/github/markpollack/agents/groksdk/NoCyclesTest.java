/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.groksdk;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * No dependency cycles between the packages of this module, at any depth, and none
 * between top-level classes inside a package. Main sources only.
 */
class NoCyclesTest {

	/**
	 * This module's compiled main classes: Maven's target/classes, or Gradle's
	 * build/classes/java/main.
	 */
	private static final JavaClasses CLASSES = new ClassFileImporter()
		.importPath(java.nio.file.Files.isDirectory(java.nio.file.Path.of("target", "classes"))
				? java.nio.file.Path.of("target", "classes")
				: java.nio.file.Path.of("build", "classes", "java", "main"));

	/** Every top-level class is a slice; its nested classes belong to it. */
	private static final SliceAssignment TOP_LEVEL_CLASS = new SliceAssignment() {
		@Override
		public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
			return SliceIdentifier.of(javaClass.getName().split("\\$")[0]);
		}

		@Override
		public String getDescription() {
			return "top-level classes";
		}
	};

	@Test
	void importsSomething() {
		// an empty import would let both rules pass vacuously
		assertFalse(CLASSES.isEmpty(),
				"no classes imported for io.github.markpollack.agents.groksdk: is the test running from the module directory with main classes compiled?");
	}

	/** Every package under the root is a slice; a cycle between any two fails. */
	@Test
	void noPackageCycles() {
		slices().matching("io.github.markpollack.agents.groksdk.(**)").should().beFreeOfCycles().check(CLASSES);
	}

	/** A cycle between classes of one package fails. */
	@Test
	void noClassCycles() {
		slices().assignedFrom(TOP_LEVEL_CLASS).should().beFreeOfCycles().check(CLASSES);
	}

}
