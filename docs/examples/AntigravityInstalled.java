import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.antigravity.*;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;

/** Installed deterministic Antigravity fixture: actual Client result, unchanged original D and raw acquisition. Journal archive route is unqualified. */
public final class AntigravityInstalled {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path fixture = Path.of(args[1]).toAbsolutePath();
        Path decisions = root.resolve("original-decision-store");
        var originalRecorder = new JournalExecutionRecorder(decisions, "antigravity-fixture");
        var acquired = new CompletableFuture<AntigravityAcquisition>();
        var release = new CountDownLatch(1);
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> values) {
                var source = (AntigravityAcquisition) values.get("antigravityAcquisition");
                acquired.complete(source);
                var deliver = originalRecorder.prepare(d);
                return () -> {
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("delivery timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    return deliver.get();
                };
            }
        };
        var provider = AntigravityClientProvider.builder().executable(fixture.toString()).workingDirectory(root)
            .acquisitionDirectory(root.resolve("captures"), "1.4.6").build();
        var options = DefaultAgentOptions.builder().workingDirectory(root.toString()).model("synthetic-model").effort("low")
            .timeout(Duration.ofSeconds(5)).environmentVariables(Map.of("FIXTURE_MARKER", "exact-environment")).build();
        try (var client = AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(root)
                .executionRecorder(recorder).build()) {
            var execution = client.goal("success").runAsync();
            require(execution.result().getResult().equals("answer:success"), "wrong result");
            var original = execution.decision().orElseThrow();
            var source = acquired.get(5, TimeUnit.SECONDS);
            require(source.complete(), source.detail());
            Path sealed = source.writeManifest(original);
            require(!(execution.recording().delivery() instanceof TerminalDelivery.Completed), "result waited for delivery");
            release.countDown();
            awaitDelivery(execution);
            require(execution.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed(), "cleanup unconfirmed");

            // The public recorder owns the canonical decision codec. Read its original bytes;
            // do not reconstruct a decision from a fixture or mutable provider response.
            Path originalEvents;
            try (var files = Files.walk(decisions)) {
                var events = files.filter(p -> p.getFileName().toString().equals("events.jsonl")).toList();
                require(events.size() == 1, "ambiguous original decision archive");
                originalEvents = events.getFirst();
            }
            var originalPackage = PackageReader.open(originalEvents);
            var binding = TerminalBindingReader.read(originalPackage.declarations);
            require(JSON.readTree(binding.decision()).required("decisionId").asText().equals(original.decisionId()), "wrong original D");
            Files.writeString(root.resolve("D.json"), binding.decision(), StandardOpenOption.CREATE_NEW);
            var envelope = (ObjectNode) JSON.readTree(originalPackage.declarations.run().member("terminal_binding").member("terminalEnvelope").text());
            Files.write(root.resolve("original-P.json"), canonical(envelope), StandardOpenOption.CREATE_NEW);
            var originalFiles = snapshot(decisions);
            require(execution.decision().orElseThrow() == original, "decision replaced");
            require(originalRecorder.prepare(original).get().equals(((TerminalDelivery.Completed) execution.recording().delivery()).bindingId()), "original replay changed binding");
            require(snapshot(decisions).equals(originalFiles), "original evidence-only store changed");
            var proof = JSON.createObjectNode().put("synthetic", true).put("nativeQualification", false).put("archiveQualified", false)
                .put("executionId", original.executionId()).put("decisionId", original.decisionId())
                .put("connectionId", original.connectionId().orElseThrow()).put("nativeId", source.nativeId().orElseThrow())
                .put("sealedManifest", sealed.toString()).put("originalDecision", root.resolve("D.json").toString())
                .put("originalEvents", originalEvents.toString()).put("originalDecisionUnchanged", true).put("cleanupConfirmed",true);
            JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("proof.json").toFile(), proof);
            require(Files.readAllLines(root.resolve("launches.jsonl")).size()==1,"provider replay");
            require(Files.readString(root.resolve("environment")).equals("exact-environment"), "environment lost");
            require(Files.readAllBytes(source.directory().resolve("stdin.bin")).length==0,"stdin contains invented prompt");
            require(Files.readString(source.directory().resolve("prompt-argument.bin")).equals("success"),"argv prompt lost");
            System.out.println("ANTIGRAVITY_INSTALLED_PASS " + proof);
        } finally { release.countDown(); }
    }
    static byte[] canonical(JsonNode value) throws Exception {
        // The wire codec requires recursively sorted object keys, without extra whitespace.
        return JSON.writeValueAsBytes(sorted(value));
    }
    static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode(); var fields = new TreeMap<String,JsonNode>();
            value.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, child) -> result.set(key, sorted(child))); return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); value.forEach(child -> result.add(sorted(child))); return result; }
        return value;
    }
    static Map<String,String> snapshot(Path root) throws Exception {
        var hashes = new TreeMap<String,String>();
        try (var files = Files.walk(root)) { for (var file : files.filter(Files::isRegularFile).toList()) hashes.put(file.toString(), hash(Files.readAllBytes(file))); }
        return hashes;
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static void awaitDelivery(AgentExecution e) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(e.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime() < end) Thread.sleep(5);
        require(e.recording().delivery() instanceof TerminalDelivery.Completed, "recording failed: " + e.recording());
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
