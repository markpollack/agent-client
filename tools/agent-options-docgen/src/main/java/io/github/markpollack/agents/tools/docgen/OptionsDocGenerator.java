/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.tools.docgen;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaSource;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code *Properties.java} source files using QDox and emits Markdown
 * reference tables for Mintlify documentation.
 *
 * <p>Usage:
 * <pre>
 * # From tools/agent-options-docgen/:
 * mvn compile exec:java -Dexec.args="/path/to/agent-client"
 *
 * # Or with explicit output directory:
 * mvn compile exec:java -Dexec.args="/path/to/agent-client /path/to/output"
 * </pre>
 */
public class OptionsDocGenerator {

	private static final Pattern CAMEL_TO_KEBAB = Pattern.compile("([a-z])([A-Z])");

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("Usage: OptionsDocGenerator <agent-client-root> [output-dir]");
			System.err.println("  agent-client-root: path to the agent-client project root");
			System.err.println("  output-dir: optional output directory (defaults to stdout)");
			System.exit(1);
		}

		Path projectRoot = Path.of(args[0]).toAbsolutePath();
		Path outputDir = args.length > 1 ? Path.of(args[1]).toAbsolutePath() : null;

		List<ProviderConfig> providers = List.of(
				new ProviderConfig("Claude", "claude-reference",
						projectRoot.resolve("agent-models/agent-claude/src/main/java/io/github/markpollack/agents/claude/autoconfigure/ClaudeAgentProperties.java")),
				new ProviderConfig("Codex", "codex-reference",
						projectRoot.resolve("agent-models/agent-codex/src/main/java/io/github/markpollack/agents/codex/autoconfigure/CodexAgentProperties.java")),
				new ProviderConfig("Gemini", "gemini-reference",
						projectRoot.resolve("agent-models/agent-gemini/src/main/java/io/github/markpollack/agents/gemini/autoconfigure/GeminiAgentProperties.java")));

		for (ProviderConfig provider : providers) {
			String markdown = generateTable(provider);
			if (outputDir != null) {
				Path outFile = outputDir.resolve(provider.outputFileName + ".md");
				Files.createDirectories(outputDir);
				try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outFile))) {
					pw.print(markdown);
				}
				System.out.println("Wrote: " + outFile);
			}
			else {
				System.out.println(markdown);
			}
		}
	}

	static String generateTable(ProviderConfig provider) throws IOException {
		JavaProjectBuilder builder = new JavaProjectBuilder();
		JavaSource source = builder.addSource(provider.sourceFile.toFile());

		JavaClass clazz = source.getClasses().get(0);
		String prefix = extractPrefix(clazz);

		List<PropertyInfo> properties = new ArrayList<>();
		for (JavaField field : clazz.getFields()) {
			if (field.isStatic() || field.isFinal()) {
				continue;
			}
			properties.add(extractProperty(field, prefix));
		}

		StringBuilder sb = new StringBuilder();
		sb.append("## ").append(provider.displayName).append(" Configuration Properties\n\n");
		sb.append("Prefix: `").append(prefix).append("`\n\n");
		sb.append("| Property | Type | Default | Description |\n");
		sb.append("|----------|------|---------|-------------|\n");

		for (PropertyInfo prop : properties) {
			sb.append("| `").append(prop.fullName).append("` ");
			sb.append("| `").append(prop.type).append("` ");
			sb.append("| ").append(prop.defaultValue).append(" ");
			sb.append("| ").append(prop.description).append(" |\n");
		}
		sb.append("\n");

		return sb.toString();
	}

	static String extractPrefix(JavaClass clazz) {
		for (JavaAnnotation annotation : clazz.getAnnotations()) {
			if (annotation.getType().getFullyQualifiedName().endsWith("ConfigurationProperties")) {
				Object prefixValue = annotation.getNamedParameter("prefix");
				if (prefixValue == null) {
					prefixValue = annotation.getNamedParameter("value");
				}
				if (prefixValue != null) {
					return prefixValue.toString().replace("\"", "");
				}
			}
		}
		return "unknown";
	}

	static PropertyInfo extractProperty(JavaField field, String prefix) {
		String fieldName = field.getName();
		String propertyName = camelToKebab(fieldName);
		String fullName = prefix + "." + propertyName;

		String type = simplifyType(field.getType().getGenericCanonicalName());
		String defaultValue = extractDefault(field);
		String description = extractDescription(field);

		return new PropertyInfo(fullName, type, defaultValue, description);
	}

	static String extractDefault(JavaField field) {
		String init = field.getInitializationExpression();
		if (init == null || init.isBlank()) {
			return "—";
		}
		init = init.trim();
		// Clean up common patterns
		if (init.startsWith("\"") && init.endsWith("\"")) {
			return "`" + init.substring(1, init.length() - 1) + "`";
		}
		if (init.equals("true") || init.equals("false")) {
			return "`" + init + "`";
		}
		if (init.startsWith("Duration.ofMinutes(")) {
			String minutes = init.replace("Duration.ofMinutes(", "").replace(")", "");
			return "`" + minutes + "m`";
		}
		if (init.startsWith("Duration.ofSeconds(")) {
			String seconds = init.replace("Duration.ofSeconds(", "").replace(")", "");
			return "`" + seconds + "s`";
		}
		if (init.startsWith("new ArrayList<>()")) {
			return "`[]`";
		}
		return "`" + init + "`";
	}

	static String extractDescription(JavaField field) {
		String comment = field.getComment();
		if (comment == null || comment.isBlank()) {
			return "—";
		}
		// Take first sentence, clean up whitespace, escape pipes for Markdown tables
		String desc = comment.trim();
		int periodIdx = desc.indexOf(". ");
		if (periodIdx > 0) {
			desc = desc.substring(0, periodIdx + 1);
		}
		// Replace newlines with spaces
		desc = desc.replaceAll("\\s+", " ").trim();
		// Escape pipe characters for Markdown table compatibility
		desc = desc.replace("|", "\\|");
		return desc;
	}

	static String camelToKebab(String camelCase) {
		Matcher matcher = CAMEL_TO_KEBAB.matcher(camelCase);
		return matcher.replaceAll("$1-$2").toLowerCase();
	}

	static String simplifyType(String genericType) {
		// Simplify common generic types
		return genericType.replace("java.lang.", "")
			.replace("java.time.", "")
			.replace("java.util.", "")
			.replace("java.nio.file.", "");
	}

	record ProviderConfig(String displayName, String outputFileName, Path sourceFile) {
	}

	record PropertyInfo(String fullName, String type, String defaultValue, String description) {
	}

}
