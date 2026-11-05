import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.*;

/**
 * Smallest real Claude fresh-start two-turn probe: the Client names the native session
 * (--session-id), witnesses the transcript's absence, runs two tiny text turns on one
 * retained generation with no built-in tools and the default permission mode, closes,
 * and exports the fresh generation claims for Journal's independent qualifier. It calls the
 * real model twice and runs only on explicit Owner activation. Args: new proof directory,
 * claude executable, native projects root ($HOME/.claude/projects), observed CLI version,
 * optional model.
 */
public final class ClaudeFreshProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path work = Files.createDirectory(root.resolve("work"));
        Path projects = Path.of(args[2]).toAbsolutePath();
        var handles = new ConcurrentHashMap<String, ClaudeRetainedCapture>();
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String, Object> evidence) {
                handles.put(d.executionId(), (ClaudeRetainedCapture) evidence.get("claudeRetainedCapture"));
                return d::decisionId;
            }
        };
        var provider = ClaudeClientProvider.builder().workingDirectory(work).claudePath(args[1]).tools(List.of())
            .maxBudgetUsd(0.50).acquisitionDirectory(root.resolve("capture"), args[3]).freshTranscript(projects).build();
        // Explicit permissions: autoApprove(false) selects --permission-mode default, never --dangerously-skip-permissions.
        var options = DefaultAgentOptions.builder().workingDirectory(work.toString()).timeout(Duration.ofSeconds(120)).autoApprove(false);
        if (args.length > 4) options.model(args[4]);
        var opts = options.build();
        var report = JSON.createObjectNode().put("kind", "client-claude-fresh-two-turn-probe").put("synthetic", false);
        var originals = new ArrayList<ExecutionDecision>();
        ClaudeRetainedCapture capture;
        try (var client = AgentClient.builder(provider).defaultOptions(opts).defaultWorkingDirectory(work)
                .defaultTimeout(Duration.ofSeconds(120)).executionRecorder(recorder).build()) {
            try (var session = client.sessions().create(s -> s.workingDirectory(work).options(opts))) {
                int i = 0;
                for (String goal : List.of("Reply with exactly the single word ALPHA and nothing else.",
                        "Reply with exactly the single word you replied last time and nothing else.")) {
                    var e = session.goal(goal).runAsync();
                    var r = e.result(Duration.ofSeconds(120));
                    report.put("turn" + (++i) + "_result", r.getResult()).set("turn" + i + "_metadata", JSON.valueToTree(r.getMetadata()));
                    originals.add(e.decision().orElseThrow());
                    await(() -> handles.containsKey(e.executionId()));
                }
                capture = handles.get(originals.getFirst().executionId());
                report.put("same_generation", handles.get(originals.getLast().executionId()) == capture);
            }
            report.put("sealed_after_close", capture.sealed());
        }
        try {
            Path manifest = capture.writeGeneration(originals);
            report.put("fresh_generation", manifest.toString());
        } catch (RuntimeException | java.io.IOException ex) {
            report.put("fresh_generation_error", ex.toString());
            report.put("diagnostic_capture", capture.write().toString());
        }
        Files.write(root.resolve("probe-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println("DONE " + root.resolve("probe-report.json"));
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError("recording timeout");
    }
}
