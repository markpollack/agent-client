import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.claude.*;

/** Two-turn installed Client + Journal decision-only proof. Local fixture, no model call. */
public final class RetainedClaudeCapture {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path child = Files.copy(Path.of(args[1]), root.resolve("child.py"));
        Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path projects = Files.createDirectories(root.resolve("native-projects/project"));
        // The fixture writes native-shaped original records here; this is not native qualification.
        var journal = JournalExecutionRecorder.claude(root.resolve("journal"), "retained-fixture");
        var handles = new ConcurrentHashMap<String, ClaudeRetainedCapture>();
        var admitted = new CountDownLatch(1);
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> evidence) {
                handles.put(d.executionId(), (ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
                var deliver = journal.prepare(d, evidence); // Current Journal deliberately records decision-only.
                return () -> {
                    try { if (!admitted.await(10, TimeUnit.SECONDS)) throw new AssertionError("next admission blocked"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    return deliver.get();
                };
            }
        };
        var provider = ClaudeClientProvider.builder().workingDirectory(root).claudePath(child.toString())
            .acquisitionDirectory(root.resolve("capture"), "deterministic-fixture").build();
        var decisions = new ArrayList<ExecutionDecision>();
        var bindings = new ArrayList<String>();
        ClaudeRetainedCapture capture;
        try (var client = AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(5)).executionRecorder(recorder).build()) {
            try (var session = client.sessions().create(s -> {})) {
                var first = session.goal("first").runAsync();
                require(first.result(Duration.ofSeconds(5)).getResult().equals("first"), "first result");
                await(() -> handles.containsKey(first.executionId()));
                capture = handles.get(first.executionId());
                require(!capture.sealed(), "first result became EOF");
                try { capture.write(); throw new AssertionError("open generation exported"); }
                catch (IllegalStateException expected) { }
                decisions.add(first.decision().orElseThrow());
                var second = session.goal("second").runAsync();
                require(second.result(Duration.ofSeconds(5)).getResult().equals("second"), "second result");
                require(!(first.recording().delivery() instanceof TerminalDelivery.Completed), "delayed recorder not exercised");
                decisions.add(second.decision().orElseThrow());
                admitted.countDown();
                await(() -> first.recording().delivery() instanceof TerminalDelivery.Completed
                    && second.recording().delivery() instanceof TerminalDelivery.Completed);
                require(handles.get(second.executionId()) == capture, "different capture generation");
                require(first.decision().orElseThrow() == decisions.getFirst(), "original D replaced");
                for (var e : List.of(first, second)) bindings.add(((TerminalDelivery.Completed)e.recording().delivery()).bindingId());
            }
            require(capture.sealed(), "close did not seal capture");
            Path manifest = capture.write(projects.getParent());
            var value = JSON.readTree(manifest.toFile());
            require(value.path("transportComplete").asBoolean(), "incomplete original transport");
            require(!value.path("archiveQualified").asBoolean(), "invented archive qualification");
            require(value.path("turns").size() == 2, "turn count");
            for (int i = 0; i < 2; i++) {
                var turn = value.path("turns").get(i);
                require(turn.path("executionId").asText().equals(decisions.get(i).executionId()), "wrong execution extent");
                verifyLocator(manifest.getParent(), turn.path("promptCandidates"), "message", i == 0 ? "first" : "second");
                verifyLocator(manifest.getParent(), turn.path("resultCandidates"), "result", i == 0 ? "first" : "second");
            }
            require(Arrays.equals(Files.readAllBytes(root.resolve("wire.jsonl")), Files.readAllBytes(manifest.getParent().resolve("stdin.bin"))), "original stdin differs");
            require(Files.readString(manifest.getParent().resolve("native/transcript.jsonl"))
                .equals(Files.readString(projects.resolve("fixture-history.jsonl"))), "original native snapshot differs");
            require(Files.readAllLines(root.resolve("launches.jsonl")).size() == 1, "replacement process");
            require(Files.readAllLines(root.resolve("prompts.jsonl")).size() == 2, "replay");
            require(decisions.getFirst().connectionId().equals(decisions.getLast().connectionId()), "generation mismatch");
            require(decisions.getFirst().sessionId().equals(decisions.getLast().sessionId()), "logical history mismatch");
            require(!decisions.getFirst().decisionId().equals(decisions.getLast().decisionId()), "same decision");
            try (var files = Files.list(root)) {
                for (var file : files.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
                    require(!ProcessHandle.of(Long.parseLong(Files.readString(file))).map(ProcessHandle::isAlive).orElse(false), "child alive");
            }
            Files.writeString(root.resolve("proof.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "decisionIds", decisions.stream().map(ExecutionDecision::decisionId).toList(),
                "executionIds", decisions.stream().map(ExecutionDecision::executionId).toList(),
                "connectionId", capture.connectionId(), "logicalSessionId", decisions.getFirst().sessionId(),
                "bindings", bindings, "capture", manifest.toString(),
                "journalBoundary", "decision-only; retained evidence revision pending Journal agreement")));
        } finally { admitted.countDown(); }
        System.out.println("RETAINED_CLIENT_CAPTURE_PASS: two decisions recorded; original sealed bytes; Journal attachments pending");
    }
    private static void verifyLocator(Path root, JsonNode candidates, String field, String expected) throws Exception {
        require(candidates.size() == 1, "missing/ambiguous locator");
        var c = candidates.get(0);
        byte[] all = Files.readAllBytes(root.resolve(c.path("stream").asText().toLowerCase(Locale.ROOT) + ".bin"));
        byte[] record = Arrays.copyOfRange(all, c.path("start").asInt(), c.path("end").asInt());
        require(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(record))
            .equals(c.path("sha256").asText()), "changed original extent");
        var raw = JSON.readTree(record).path(field);
        require((field.equals("message") ? raw.path("content").asText() : raw.asText()).equals(expected), "swapped extent");
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        require(condition.getAsBoolean(), "recording timeout");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
