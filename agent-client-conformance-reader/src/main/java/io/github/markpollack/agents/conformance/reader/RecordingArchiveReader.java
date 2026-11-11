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
import java.util.Comparator;
import java.util.HashSet;
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
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;

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
 * expectation. Otherwise Journal's qualifier re-derives it from the archived client
 * manifest and the report says whether the recorder's {@code native-<executionId>} copy
 * matches; the recorder's copy is used only when that cannot be done.
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

	/**
	 * A candidate is one recording: a root core together with the child cores it links (a
	 * sub-agent's, for example), which Journal's reader opens with it. A child core is
	 * never a candidate of its own; one that no root core in the store links is reported.
	 * A root core whose binding cannot be read is reported as unreadable, not thrown.
	 */
	static ObjectNode read(Path store, Map<String, String> selector) throws IOException {
		var assemblyOverride = selector.remove("assembly");
		var exportTarget = selector.remove("export");
		var scan = Scan.of(store);
		var matches = new ArrayList<Match>();
		ArrayNode unreadable = JSON.createArrayNode();
		for (var root : scan.roots().entrySet()) {
			Match match = match(root.getKey(), root.getValue(), unreadable);
			if (match != null && matches(match.decision, selector))
				matches.add(match);
		}
		ObjectNode report = JSON.createObjectNode();
		report.put("forbiddenClassesAbsent", true);
		report.put("candidates", scan.roots().size());
		report.put("matches", matches.size());
		report.set("unreadable", unreadable);
		ArrayNode unlinked = report.putArray("unlinkedChildCores");
		scan.unlinked().forEach(path -> unlinked.add(path.toString()));
		if (matches.size() != 1)
			return report;
		var match = matches.get(0);
		describe(report, store, match, assemblyOverride, exportTarget);
		return report;
	}

	/** The recording's decision, or null when its binding cannot be read. */
	private static Match match(Path events, OcsfPackage pkg, ArrayNode unreadable) throws IOException {
		String reason;
		if (pkg.declarations == null)
			reason = "not a standalone declared event file";
		else {
			try {
				var binding = TerminalBindingReader.read(pkg.declarations);
				if (binding != null)
					return new Match(events, pkg, binding, JSON.readTree(binding.decision()));
				reason = "no terminal binding";
			}
			catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException ex) {
				reason = "terminal binding unreadable: " + ex.getMessage();
			}
		}
		ObjectNode entry = unreadable.addObject();
		entry.put("events", events.toString());
		entry.put("reason", reason);
		return null;
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
		ArrayNode cores = report.putArray("cores");
		ArrayNode serious = report.putArray("seriousDiagnostics");
		for (OcsfPackage core : match.pkg.all()) {
			cores.add(core.label());
			String prefix = core == match.pkg ? "" : core.label() + ": ";
			core.diagnostics.stream()
				.filter(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED)
				.forEach(d -> serious.add(prefix + d));
		}
		Path sources = match.events.getParent().resolve("sources");
		Path assembly = assembly(report, store.resolve("native-" + executionId), assemblyOverride);
		verify(report, match.events, sources, assembly);
		export(report, match.pkg, exportTarget);
	}

	/**
	 * Review F9: the assembly expectation verification uses. When the archive keeps the
	 * client's original manifest, Journal's own qualifier re-derives the expectation from
	 * it, so the recorder's copy is checked rather than trusted. {@code assemblySource}
	 * says where it came from, and {@code assemblyMatchesRecorder} whether the recorder's
	 * copy has the same bytes.
	 */
	private static Path assembly(ObjectNode report, Path archived, String override) throws IOException {
		if (override != null) {
			report.put("assemblySource", "override");
			return Path.of(override);
		}
		Path recorderCopy = archived.resolve("assembly-expectation.json");
		Path original = archived.resolve("client-acquisition.json");
		if (!Files.isRegularFile(original)) {
			report.put("assemblySource", "recorder");
			return recorderCopy;
		}
		String profile = JSON.readTree(original.toFile()).path("profile").asText();
		var qualifier = AcquisitionQualifier.forProfile(profile);
		if (qualifier.isEmpty()) {
			report.put("assemblySource", "recorder");
			report.put("assemblyTckDetail", "No Journal qualifier for acquisition profile " + profile);
			return recorderCopy;
		}
		try {
			Path derived = qualifier.get()
				.qualify(original, Files.createTempDirectory("tck-assembly").resolve("qualified"))
				.assemblyExpectation();
			report.put("assemblySource", "tck");
			report.put("assemblyMatchesRecorder", Files.isRegularFile(recorderCopy)
					&& java.util.Arrays.equals(Files.readAllBytes(derived), Files.readAllBytes(recorderCopy)));
			return derived;
		}
		catch (IOException | RuntimeException ex) {
			report.put("assemblySource", "recorder");
			report.put("assemblyTckDetail", "Journal's qualifier could not re-derive the expectation: " + ex);
			return recorderCopy;
		}
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
	 * mentions cost. Journal refuses to export a recording with a refused or unvalidated
	 * core, a child's included; the report then says so and nothing is written.
	 */
	private static void export(ObjectNode report, OcsfPackage pkg, String target) throws IOException {
		var writer = new StringWriter();
		try {
			CanonicalExport.write(pkg, writer);
		}
		catch (IllegalArgumentException refused) {
			report.put("exportRefused", refused.getMessage());
			return;
		}
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

	/**
	 * The store's recordings: every root core, opened with the child cores it links, and
	 * the child cores no root core links.
	 */
	private record Scan(Map<Path, OcsfPackage> roots, List<Path> unlinked) {

		static Scan of(Path store) throws IOException {
			List<Path> files;
			try (Stream<Path> walk = Files.walk(store)) {
				// Shallower first, so a root core is opened before the child cores it
				// links.
				files = walk.filter(p -> p.getFileName().toString().equals("events.jsonl"))
					.map(Scan::normal)
					.sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Comparator.naturalOrder()))
					.toList();
			}
			var roots = new LinkedHashMap<Path, OcsfPackage>();
			var linked = new HashSet<Path>();
			var children = new ArrayList<Path>();
			for (Path events : files) {
				if (linked.contains(events))
					continue;
				var pkg = PackageReader.open(events);
				pkg.all()
					.stream()
					.filter(core -> core != pkg)
					.forEach(core -> linked.add(normal(core.dir.resolve("events.jsonl"))));
				if (pkg.manifest != null && pkg.manifest.parent() != null)
					children.add(events);
				else
					roots.put(events, pkg);
			}
			children.removeIf(linked::contains);
			return new Scan(roots, children);
		}

		private static Path normal(Path path) {
			return path.toAbsolutePath().normalize();
		}

	}

	private record Match(Path events, OcsfPackage pkg, TerminalBindingReader.Binding binding, JsonNode decision) {
	}

}
