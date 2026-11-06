/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.tck.ocsf.CanonicalExport;
import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;

/**
 * Independent reader of one Client recording archive. It runs in its own JVM whose
 * classpath is Journal's capture TCK and its runtime dependencies only, so no Client,
 * provider SDK or Journal writer code can take part in the verdict.
 *
 * <p>
 * Arguments: the archive store, a new report file, then {@code key=value} selectors. A
 * selector names a decision field ({@code executionId}, {@code decisionId}) or a
 * correlation field ({@code workflowRunId} and the other five); exactly one archived
 * decision must match all of them. {@code assembly=<path>} overrides the assembly
 * expectation, which otherwise is the recorder's {@code native-<executionId>} copy, and
 * {@code export=<new path>} keeps the canonical export for a downstream consumer.
 *
 * <p>
 * The report states what the archive says. It decides nothing beyond Journal's own
 * acceptance verdict; the caller asserts on it.
 */
public final class RecordingArchiveReader {

	/** Classes whose presence would mean the verdict is not independent. */
	static final List<String> FORBIDDEN = List.of("io.github.markpollack.agents.client.AgentClient",
			"io.github.markpollack.agents.client.journal.JournalExecutionRecorder",
			"io.github.markpollack.agents.codex.CodexClientProvider",
			"io.github.markpollack.agents.claude.ClaudeClientProvider",
			"io.github.markpollack.journal.ocsf.writer.TerminalReceiver");

	private static final Set<String> CORRELATION = Set.of("experimentId", "experimentRunId", "experimentInvocationId",
			"workflowRunId", "workflowInvocationId", "workflowAttemptId");

	private static final ObjectMapper JSON = new ObjectMapper();

	private RecordingArchiveReader() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 3)
			throw new IllegalArgumentException("usage: <store> <new-report.json> key=value...");
		for (String name : FORBIDDEN)
			if (loadable(name))
				throw new IllegalStateException("Reader classpath is not independent: " + name);
		var selector = new LinkedHashMap<String, String>();
		for (int i = 2; i < args.length; i++) {
			int split = args[i].indexOf('=');
			if (split < 1)
				throw new IllegalArgumentException("selector must be key=value: " + args[i]);
			selector.put(args[i].substring(0, split), args[i].substring(split + 1));
		}
		ObjectNode report = read(Path.of(args[0]).toAbsolutePath(), selector);
		Files.write(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
				java.nio.file.StandardOpenOption.CREATE_NEW);
	}

	static ObjectNode read(Path store, Map<String, String> selector) throws IOException {
		var assemblyOverride = selector.remove("assembly");
		var exportTarget = selector.remove("export");
		var matches = new ArrayList<Match>();
		List<Path> candidates;
		try (Stream<Path> files = Files.walk(store)) {
			candidates = files.filter(p -> p.getFileName().toString().equals("events.jsonl")).sorted().toList();
		}
		for (Path events : candidates) {
			var pkg = PackageReader.open(events);
			if (pkg.declarations == null)
				continue;
			var binding = TerminalBindingReader.read(pkg.declarations);
			JsonNode decision = JSON.readTree(binding.decision());
			if (matches(decision, selector))
				matches.add(new Match(events, pkg, binding, decision));
		}
		ObjectNode report = JSON.createObjectNode();
		report.put("forbiddenClassesAbsent", true);
		report.put("candidates", candidates.size());
		report.put("matches", matches.size());
		if (matches.size() != 1)
			return report;
		var match = matches.get(0);
		describe(report, store, match, assemblyOverride, exportTarget);
		return report;
	}

	private static void describe(ObjectNode report, Path store, Match match, String assemblyOverride,
			String exportTarget) throws IOException {
		var envelope = JSON
			.readTree(match.pkg.declarations.run().member("terminal_binding").member("terminalEnvelope").text());
		JsonNode association = JSON.readTree(match.binding.association());
		String executionId = match.decision.path("executionId").asText();
		report.put("events", match.events.toString());
		report.put("profile", envelope.path("profile").asText());
		report.put("producerNamespace", match.binding.producerNamespace());
		for (String key : List.of("executionId", "decisionId", "sessionId", "connectionId"))
			report.set(key, match.decision.path(key));
		report.set("correlation", match.decision.path("correlation"));
		report.set("outcome", match.decision.path("outcome"));
		report.put("acquisition", association.path("acquisition").asText());
		report.set("missing", association.path("missing"));
		report.put("sources", association.path("sources").size());
		ArrayNode serious = report.putArray("seriousDiagnostics");
		match.pkg.diagnostics.stream()
			.filter(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED)
			.forEach(d -> serious.add(d.toString()));
		Path sources = match.events.getParent().resolve("sources");
		Path assembly = assemblyOverride != null ? Path.of(assemblyOverride)
				: store.resolve("native-" + executionId).resolve("assembly-expectation.json");
		verify(report, match.events, sources, assembly);
		export(report, match.pkg, exportTarget);
	}

	/**
	 * Journal's own combined verification, when the archive carries qualified sources.
	 */
	private static void verify(ObjectNode report, Path events, Path sources, Path assembly) throws IOException {
		Path acquisition = sources.resolve("acquisition.json");
		if (!Files.isRegularFile(acquisition) || !Files.isRegularFile(assembly)) {
			report.put("acceptance", "NOT_VERIFIED");
			report.put("acceptanceDetail", "no qualified acquisition manifest or assembly expectation");
			return;
		}
		var out = new ByteArrayOutputStream();
		var err = new ByteArrayOutputStream();
		int exit;
		try (var o = new PrintStream(out, true, StandardCharsets.UTF_8);
				var e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
			exit = OcsfReaderTool.run(new String[] { "verify", events.toString(), sources.toString(), "--acquisition",
					acquisition.toString(), "--assembly", assembly.toString() }, o, e);
		}
		String text = out.toString(StandardCharsets.UTF_8);
		String verdict = text.lines()
			.filter(line -> line.startsWith("acceptance: "))
			.map(line -> line.substring("acceptance: ".length()).split(" ", 2)[0])
			.reduce((first, second) -> second)
			.orElse("NONE");
		report.put("acceptance", exit == 0 ? verdict : verdict + " (exit " + exit + ")");
		report.put("acceptanceDetail", text + err.toString(StandardCharsets.UTF_8));
	}

	/**
	 * Every native usage object the canonical export carries, and every member name that
	 * mentions cost.
	 */
	private static void export(ObjectNode report, OcsfPackage pkg, String target) throws IOException {
		var writer = new StringWriter();
		CanonicalExport.write(pkg, writer);
		if (target != null) {
			Files.writeString(Path.of(target), writer.toString(), StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE_NEW);
			report.put("export", target);
		}
		var usage = new LinkedHashSet<JsonNode>();
		var cost = new LinkedHashSet<String>();
		var rows = writer.toString().lines().filter(line -> !line.isBlank()).toList();
		for (String line : rows)
			collect(JSON.readTree(line), usage, cost);
		report.put("canonicalRows", rows.size());
		report.set("usage", JSON.valueToTree(usage));
		report.set("costMembers", JSON.valueToTree(cost));
	}

	private static void collect(JsonNode node, Set<JsonNode> usage, Set<String> cost) {
		if (node.isObject()) {
			if (node.has("input_tokens") && node.has("output_tokens"))
				usage.add(node);
			node.fields().forEachRemaining(field -> {
				if (field.getKey().toLowerCase(java.util.Locale.ROOT).contains("cost"))
					cost.add(field.getKey());
				collect(field.getValue(), usage, cost);
			});
		}
		else if (node.isArray())
			node.forEach(child -> collect(child, usage, cost));
	}

	private static boolean matches(JsonNode decision, Map<String, String> selector) {
		for (var entry : selector.entrySet()) {
			JsonNode value = CORRELATION.contains(entry.getKey()) ? decision.path("correlation").path(entry.getKey())
					: decision.path(entry.getKey());
			if (!value.isTextual() || !value.asText().equals(entry.getValue()))
				return false;
		}
		return true;
	}

	private static boolean loadable(String name) {
		try {
			Class.forName(name, false, RecordingArchiveReader.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException expected) {
			return false;
		}
	}

	private record Match(Path events, OcsfPackage pkg, TerminalBindingReader.Binding binding, JsonNode decision) {
	}

}
