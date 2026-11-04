import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.codex.*;

/**
 * Smallest real two-turn Codex probe: observes resume parsing, history continuity, emitted
 * identity and usage scope on the pinned CLI. Runs only on explicit Owner activation; it
 * calls the real model twice. Args: proof directory (new), codex executable, sessions root
 * ($CODEX_HOME/sessions), optional model.
 */
public final class CodexProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path work = Files.createDirectory(root.resolve("work"));
        var sealed = new java.util.concurrent.LinkedBlockingQueue<Map.Entry<String, CodexAcquisition>>();
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> values) {
                sealed.add(Map.entry(d.executionId(), (CodexAcquisition) values.get("codexAcquisition")));
                return () -> "ack";
            }
        };
        var provider = CodexClientProvider.builder().codexPath(args[1]).workingDirectory(work)
            .nativeSessionsDirectory(Path.of(args[2])).acquisitionDirectory(root.resolve("captures"), "0.166.0")
            .skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(work.toString()).timeout(Duration.ofSeconds(120));
        if (args.length > 3) options.model(args[3]);
        var opts = options.build();
        var report = JSON.createObjectNode().put("kind", "client-codex-native-two-turn-probe").put("synthetic", false);
        try (var client = AgentClient.builder(provider).defaultOptions(opts).defaultWorkingDirectory(work).executionRecorder(recorder).build();
             var session = client.sessions().create(s -> s.workingDirectory(work).options(opts))) {
            var first = session.goal("Reply with exactly the single word ALPHA and nothing else.").runAsync();
            var r1 = first.result();
            report.put("turn1_result", r1.getResult()).set("turn1_metadata", JSON.valueToTree(r1.getMetadata()));
            var second = session.goal("Reply with exactly the single word you replied last time and nothing else.").runAsync();
            var r2 = second.result();
            report.put("turn2_result", r2.getResult()).set("turn2_metadata", JSON.valueToTree(r2.getMetadata()));
            for (int i = 0; i < 2; i++) {
                var entry = sealed.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                if (entry == null) { report.put("acquisition_" + i, "NOT_DELIVERED"); continue; }
                var decision = entry.getKey().equals(first.executionId()) ? first.decision() : second.decision();
                report.put("acquisition_" + entry.getKey(), entry.getValue().complete() ? entry.getValue().writeManifest(decision.orElseThrow()).toString() : "INCOMPLETE: " + entry.getValue().detail());
            }
            second.awaitTermination(Duration.ofSeconds(30));
        }
        Files.write(root.resolve("probe-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println("DONE " + root.resolve("probe-report.json") + " (thread manifest under captures/codex-thread-*/)");
    }
}
