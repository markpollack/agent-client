import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.*;

/**
 * Deterministic fresh-start two-turn generation: the Client names the native session, witnesses the
 * transcript's absence, two text turns, close, then exports the fresh generation claims for Journal's
 * independent qualifier. No native CLI/model is invoked. Args: new root, retained-child.py.
 */
public final class RetainedClaudeFresh {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path child = Files.copy(Path.of(args[1]), root.resolve("child.py"));
        Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var handles = new ConcurrentHashMap<String, ClaudeRetainedCapture>();
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> evidence) {
                handles.put(d.executionId(), (ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
                return d::decisionId;
            }
        };
        var provider = ClaudeClientProvider.builder().workingDirectory(root).claudePath(child.toString())
            .acquisitionDirectory(root.resolve("capture"), "2.1.316").freshTranscript(root.resolve("native-projects")).build();
        var originals = new ArrayList<ExecutionDecision>();
        ClaudeRetainedCapture capture;
        try (var client = AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(5)).executionRecorder(recorder).build()) {
            try (var session = client.sessions().create(s -> {})) {
                for (String prompt : List.of("first", "second")) {
                    var e = session.goal(prompt).runAsync();
                    require(e.result(Duration.ofSeconds(5)).getResult().equals(prompt), "wrong text result");
                    originals.add(e.decision().orElseThrow());
                    await(() -> handles.containsKey(e.executionId()));
                }
                capture = handles.get(originals.getFirst().executionId());
                require(handles.get(originals.getLast().executionId()) == capture, "generation replaced");
                require(!capture.sealed(), "turn result became EOF");
            }
            require(capture.sealed(), "session close did not seal");
        }
        Path manifest = capture.writeGeneration(originals);
        require(manifest.getFileName().toString().equals("retained-fresh-generation.json"), "not a fresh generation");
        require(Files.readAllLines(root.resolve("launches.jsonl")).size() == 1, "replacement child");
        require(Files.readAllLines(root.resolve("prompts.jsonl")).size() == 2, "prompt replay");
        System.out.println("RETAINED_CLAUDE_FRESH_PASS " + manifest);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        require(condition.getAsBoolean(), "recording timeout");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
