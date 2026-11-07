/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.conformance.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Journal archive whose recording has a child core: the child is read as part of its
 * recording, never as a candidate of its own, and the reader reports instead of throwing.
 * The archives are Journal's sealed {@code terminal-child} cases.
 */
class RecordingArchiveReaderTest {

	private static final Path CASES = Path.of("src/test/resources/terminal-child");

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path temp;

	private ObjectNode read(Path store, Path assembly) throws IOException {
		var selector = new LinkedHashMap<String, String>();
		selector.put("executionId", "execution-1");
		selector.put("assembly", assembly.toString());
		selector.put("export", this.temp.resolve("export-" + System.nanoTime() + ".jsonl").toString());
		return RecordingArchiveReader.read(store.toAbsolutePath(), selector);
	}

	private ObjectNode readCase(String name) throws IOException {
		return read(CASES.resolve(name).resolve("store"), CASES.resolve(name).resolve("assembly-expectation.json"));
	}

	private static List<String> texts(JsonNode array) {
		var out = new ArrayList<String>();
		array.forEach(node -> out.add(node.asText()));
		return out;
	}

	@Test
	void aSubAgentChildIsReadAsPartOfItsRecording() throws IOException {
		ObjectNode report = readCase("claude-sub-agent");
		assertThat(report.path("candidates").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(report.path("matches").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(texts(report.path("cores"))).containsExactly("main", "child:toolu_AGENT");
		assertThat(report.path("acceptance").asText()).as(report.path("acceptanceDetail").asText())
			.isEqualTo("ACCEPTED");
		assertThat(report.path("seriousDiagnostics")).isEmpty();
		assertThat(report.path("unlinkedChildCores")).isEmpty();
		assertThat(contextRows(Path.of(report.path("export").asText()))).containsExactly("main", "child:toolu_AGENT");
	}

	@Test
	void aNestedSubAgentIsReadWithBothChildCores() throws IOException {
		ObjectNode report = readCase("claude-sub-agent-nested");
		assertThat(report.path("candidates").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(report.path("matches").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(texts(report.path("cores"))).containsExactlyInAnyOrder("main", "child:toolu_AGENT",
				"child:toolu_INNER");
		assertThat(report.path("acceptance").asText()).as(report.path("acceptanceDetail").asText())
			.isEqualTo("ACCEPTED");
		assertThat(contextRows(Path.of(report.path("export").asText()))).hasSize(3);
	}

	@Test
	void aPhantomHeartbeatChildIsReportedAsJournalJudgesItNotThrown() throws IOException {
		ObjectNode report = readCase("claude-heartbeat-bd04735");
		assertThat(report.path("candidates").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(report.path("matches").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(texts(report.path("cores"))).containsExactly("main", "child:toolu_MCP");
		assertThat(report.path("acceptance").asText()).startsWith("DEFECTIVE");
	}

	@Test
	void anArchiveWithoutAChildStillReadsAsOneRecording() throws IOException {
		ObjectNode report = readCase("claude-heartbeat");
		assertThat(report.path("candidates").asInt()).isEqualTo(1);
		assertThat(report.path("matches").asInt()).isEqualTo(1);
		assertThat(texts(report.path("cores"))).containsExactly("main");
		assertThat(report.path("acceptance").asText()).isEqualTo("ACCEPTED");
	}

	@Test
	void aChildCoreNoRecordingLinksIsReportedAndIsNoCandidate() throws IOException {
		Path store = this.temp.resolve("orphan");
		copy(rootEvents(CASES.resolve("claude-sub-agent/store")).getParent().resolve("children/toolu_AGENT"),
				store.resolve("prepared/stray"));
		ObjectNode report = read(store, CASES.resolve("claude-sub-agent/assembly-expectation.json"));
		assertThat(report.path("candidates").asInt()).isZero();
		assertThat(report.path("matches").asInt()).isZero();
		assertThat(texts(report.path("unlinkedChildCores"))).singleElement()
			.asString()
			.endsWith("prepared/stray/events.jsonl");
	}

	@Test
	void aRecordingWhoseBindingCannotBeReadIsReportedNotThrown() throws IOException {
		Path store = this.temp.resolve("unreadable");
		copy(CASES.resolve("claude-heartbeat/store"), store);
		Path events = rootEvents(store);
		List<String> lines = new ArrayList<>(Files.readAllLines(events));
		for (int i = 0; i < lines.size(); i++) {
			JsonNode line = JSON.readTree(lines.get(i));
			if (line.path("run").has("terminal_binding")) {
				((ObjectNode) line.path("run").path("terminal_binding")).put("terminalEnvelope", "{}");
				lines.set(i, JSON.writeValueAsString(line));
			}
		}
		Files.write(events, lines);
		ObjectNode report = read(store, CASES.resolve("claude-heartbeat/assembly-expectation.json"));
		assertThat(report.path("candidates").asInt()).isEqualTo(1);
		assertThat(report.path("matches").asInt()).isZero();
		assertThat(report.path("unreadable")).singleElement()
			.satisfies(entry -> assertThat(entry.path("events").asText()).endsWith("events.jsonl"))
			.satisfies(entry -> assertThat(entry.path("reason").asText()).isNotBlank());
	}

	@Test
	void aRecordingWhoseExportJournalRefusesIsReportedNotThrown() throws IOException {
		Path store = this.temp.resolve("refused-child");
		copy(CASES.resolve("claude-sub-agent/store"), store);
		Path child = rootEvents(store).getParent().resolve("children/toolu_AGENT/events.jsonl");
		List<String> lines = new ArrayList<>(Files.readAllLines(child));
		ObjectNode format = (ObjectNode) JSON.readTree(lines.get(0));
		format.with("format").put("package_version", "9.9.9-unsupported");
		lines.set(0, JSON.writeValueAsString(format));
		Files.write(child, lines);
		ObjectNode report = read(store, CASES.resolve("claude-sub-agent/assembly-expectation.json"));
		assertThat(report.path("matches").asInt()).as(report.toString()).isEqualTo(1);
		assertThat(report.path("acceptance").asText()).isNotBlank();
		assertThat(report.path("exportRefused").asText()).as(report.toString()).contains("child:toolu_AGENT");
		assertThat(report.has("export")).isFalse();
	}

	/**
	 * The root core's event file in a store: the one not under a {@code children}
	 * directory.
	 */
	private static Path rootEvents(Path store) throws IOException {
		try (Stream<Path> files = Files.walk(store)) {
			return files
				.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/"))
				.findFirst()
				.orElseThrow();
		}
	}

	/** The core label of every canonical-core context row in an export. */
	private static List<String> contextRows(Path export) throws IOException {
		var out = new ArrayList<String>();
		for (String line : Files.readAllLines(export)) {
			JsonNode row = JSON.readTree(line);
			if (row.path("kind").asText().equals("agent-journal/canonical-core"))
				out.add(row.path("core").asText());
		}
		return out;
	}

	private static void copy(Path from, Path to) throws IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path source : files.toList()) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source))
					Files.createDirectories(target);
				else
					Files.copy(source, target);
			}
		}
	}

}
