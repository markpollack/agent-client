import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.claude.*;

/** Deterministic installed two-turn archive proof. No native CLI/model is invoked. */
public final class RetainedClaudeArchive {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path child = Files.copy(Path.of(args[1]), root.resolve("child.py"));
        Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path projects = Files.createDirectories(root.resolve("native-projects/project"));
        // An observed empty file is evidence; an absent file is not a known empty prefix.
        Path transcript = Files.createFile(projects.resolve("fixture-history.jsonl"));
        Path store = root.resolve("journal");
        var journal = JournalExecutionRecorder.retainedClaude(store, "retained-fixture");
        var handles = new ConcurrentHashMap<String,ClaudeRetainedCapture>();
        var release = new CountDownLatch(1);
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> evidence) {
                var deliver = journal.prepare(d);
                handles.put(d.executionId(), (ClaudeRetainedCapture)evidence.get("claudeRetainedCapture"));
                return () -> {
                    try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("turn progress blocked"); }
                    catch (InterruptedException ex) { throw new AssertionError(ex); }
                    return deliver.get();
                };
            }
        };
        var provider = ClaudeClientProvider.builder().workingDirectory(root).claudePath(child.toString())
            .acquisitionDirectory(root.resolve("capture"), "2.1.316").retainedTranscript(transcript).build();
        var executions = new ArrayList<AgentExecution>();
        var originals = new ArrayList<ExecutionDecision>();
        var initialStates = new ArrayList<TerminalDelivery>();
        ClaudeRetainedCapture capture;
        try (var client = AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(5)).executionRecorder(recorder).build()) {
            try (var session = client.sessions().create(s -> {})) {
                for (String prompt : List.of("first", "second")) {
                    var e = session.goal(prompt).runAsync();
                    require(e.result(Duration.ofSeconds(5)).getResult().equals(prompt), "wrong text result");
                    executions.add(e); originals.add(e.decision().orElseThrow());
                }
                require(!(executions.getFirst().recording().delivery() instanceof TerminalDelivery.Completed), "delivery latch was bypassed");
                await(() -> handles.size() == 2);
                capture = handles.get(executions.getFirst().executionId());
                require(handles.get(executions.getLast().executionId()) == capture, "generation replaced");
                require(!capture.sealed(), "turn result became EOF");
                try { capture.writeGeneration(originals); throw new AssertionError("premature complete export"); }
                catch (IllegalStateException expected) { }
                release.countDown();
                await(() -> executions.stream().allMatch(e -> e.recording().delivery() instanceof TerminalDelivery.Completed));
                for (var e : executions) initialStates.add(e.recording().delivery());
            }
            require(capture.sealed(), "session close did not seal");
            Path manifest = capture.writeGeneration(originals);
            var frozen = new LinkedHashMap<Path,byte[]>();
            try (var paths = Files.walk(store)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) frozen.put(p, Files.readAllBytes(p));
            }
            // Explicit post-close secondary delivery. Reversed order is allowed, never required.
            for (int i : new int[]{1, 0}) {
                var e = executions.get(i);
                String ack = journal.attach(e.executionId(), manifest);
                require(ack.equals(journal.attach(e.executionId(), manifest)), "final retry changed ACK");
                require(journal.attachment(e.executionId()).delivery() instanceof TerminalDelivery.Completed, "attachment failed");
                require(e.decision().orElseThrow() == originals.get(i), "decision object changed");
                require(e.recording().delivery().equals(initialStates.get(i)), "initial delivery changed");
                require(journal.prepare(originals.get(i)).get().equals(((TerminalDelivery.Completed)initialStates.get(i)).bindingId()), "initial retry changed ACK");
            }
            for (var item : frozen.entrySet()) require(Arrays.equals(item.getValue(), Files.readAllBytes(item.getKey())), "original D/P/ACK artifact changed");
            require(Files.readAllLines(root.resolve("launches.jsonl")).size() == 1, "replacement child");
            require(Files.readAllLines(root.resolve("prompts.jsonl")).size() == 2, "prompt replay");
            require(Arrays.equals(Files.readAllBytes(root.resolve("wire.jsonl")), Files.readAllBytes(manifest.getParent().resolve("stdin.bin"))), "stdin changed");
            require(Arrays.equals(Files.readAllBytes(transcript), Files.readAllBytes(manifest.getParent().resolve("native/transcript.jsonl"))), "native bytes changed");
            try (var paths = Files.list(root)) {
                for (Path pid : paths.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
                    require(!ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false), "fixture child alive");
            }
            var proof = new LinkedHashMap<String,Object>();
            proof.put("kind", "deterministic-retained-client-proof/1");
            proof.put("generation", manifest.toString());
            proof.put("executions", executions.stream().map(AgentExecution::executionId).toList());
            proof.put("decisions", originals.stream().map(ExecutionDecision::decisionId).toList());
            proof.put("logicalSessionId", originals.getFirst().sessionId());
            proof.put("connectionId", capture.connectionId());
            proof.put("initialBindings", initialStates.stream().map(s -> ((TerminalDelivery.Completed)s).bindingId()).toList());
            proof.put("finalBindings", executions.stream().map(e -> ((TerminalDelivery.Completed)journal.attachment(e.executionId()).delivery()).bindingId()).toList());
            proof.put("progressIndependentOfInitialDelivery", true);
            proof.put("originalFilesUnchanged", frozen.size());
            proof.put("nativeQualification", false);
            JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("proof.json").toFile(), proof);
        } finally { release.countDown(); }
        System.out.println("RETAINED_CLIENT_ARCHIVE_PASS: two successful original decisions, one generation, final attachments after close");
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        require(condition.getAsBoolean(), "recording timeout");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
