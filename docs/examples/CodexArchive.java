import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.codex.*;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;

/** Installed deterministic Codex fixture: actual Client result and D, then qualified archive. */
public final class CodexArchive {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path fixture = Path.of(args[1]).toAbsolutePath();
        Path decisions = root.resolve("original-decision-store");
        var originalRecorder = new JournalExecutionRecorder(decisions, "codex-fixture");
        var acquired = new CompletableFuture<CodexAcquisition>();
        var release = new CountDownLatch(1);
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> values) {
                var source = (CodexAcquisition) values.get("codexAcquisition");
                acquired.complete(source);
                var deliver = originalRecorder.prepare(d);
                return () -> {
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("delivery timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    return deliver.get();
                };
            }
        };
        var provider = CodexClientProvider.builder().codexPath(fixture.toString()).workingDirectory(root)
            .nativeSessionsDirectory(root.resolve("sessions")).acquisitionDirectory(root.resolve("captures"), "0.166.0")
            .skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(root.toString()).model("synthetic-model").effort("low")
            .timeout(Duration.ofSeconds(5)).environmentVariables(Map.of("FIXTURE_SESSIONS", root.resolve("sessions").toString())).build();
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
            var qualified = io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition.qualify(sealed, root.resolve("qualified"));
            var manifest = JSON.readTree(Files.readAllBytes(qualified.manifest()));
            var association = (ObjectNode) JSON.readTree(binding.association());
            association.put("acquisition", "COMPLETED"); association.putNull("priorRevisionId"); association.putArray("missing");
            ArrayNode sources = association.putArray("sources");
            for (var item : manifest.required("sources")) sources.add(occurrence(qualified.manifest().getParent(), item, manifest, original));
            sources.add(occurrence(qualified.manifest().getParent(), JSON.createObjectNode().put("path", "acquisition.json")
                .put("role", "manifest").put("origin", "qualified-codex-manifest"), manifest, original));
            // This separate store receives its initial complete binding exactly once.
            // The original evidence-only package and its binding remain untouched.
            envelope.put("profile", "client-terminal-codex-0.166.0/1");
            envelope.put("associationBytes", Base64.getEncoder().encodeToString(canonical(association)));
            byte[] frozen = canonical(envelope);
            Files.write(root.resolve("P.json"), frozen, StandardOpenOption.CREATE_NEW);
            Path store = root.resolve("archive");
            var receiver = new TerminalReceiver(store, "codex-fixture", WriterOptions.defaults());
            String ack = receiver.receive(frozen, qualified.manifest().getParent());
            require(snapshot(decisions).equals(originalFiles), "original evidence-only package changed");
            try {
                new TerminalReceiver(decisions, "codex-fixture", WriterOptions.defaults()).receive(frozen, qualified.manifest().getParent());
                throw new AssertionError("profile replacement accepted in original receiver");
            } catch (IllegalArgumentException expected) { }
            require(snapshot(decisions).equals(originalFiles), "conflict changed original package");
            var beforeReplay = snapshot(root);
            require(receiver.receive(frozen, qualified.manifest().getParent()).equals(ack), "replay changed ACK");
            require(originalRecorder.prepare(original).get().equals(((TerminalDelivery.Completed) execution.recording().delivery()).bindingId()), "original replay changed binding");
            require(snapshot(root).equals(beforeReplay), "replay changed original files");
            require(execution.decision().orElseThrow() == original, "decision replaced");
            Path events = null;
            for (var artifact : JSON.readTree(ack).required("artifacts")) {
                String path = artifact.required("relativePath").asText();
                if (path.endsWith("/events.jsonl")) events = store.resolve(path);
            }
            require(events != null, "missing final events");
            var proof = JSON.createObjectNode().put("synthetic", true).put("nativeQualification", false)
                .put("executionId", original.executionId()).put("decisionId", original.decisionId())
                .put("sessionId", original.sessionId()).put("connectionId", original.connectionId().orElseThrow())
                .put("nativeSessionId", manifest.required("run").required("session_uid").asText())
                .put("nativeTurnId", manifest.required("turns").get(0).required("native_turn_uid").asText())
                .put("sealedManifest", sealed.toString()).put("events", events.toString())
                .put("originalEvents", originalEvents.toString()).put("originalStore", decisions.toString())
                .put("originalPackageUnchanged", true).put("sameReceiverProfileConflictRejected", true)
                .put("assembly", qualified.assemblyExpectation().toString()).put("originalDecision", root.resolve("D.json").toString())
                .put("bindingId", JSON.readTree(ack).required("bindingId").asText()).put("unchangedReplayFiles", beforeReplay.size());
            JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("proof.json").toFile(), proof);
            require(Files.readAllLines(root.resolve("launches.jsonl")).size() == 1, "provider replay");
            System.out.println("CODEX_INSTALLED_ARCHIVE_PASS " + proof);
        } finally { release.countDown(); }
    }
    static ObjectNode occurrence(Path base, JsonNode source, JsonNode manifest, ExecutionDecision d) throws Exception {
        String path = source.required("path").asText(); byte[] bytes = Files.readAllBytes(base.resolve(path));
        String stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(source.required("role").asText());
        return JSON.createObjectNode().put("sourceId", path).put("relativePath", path).put("origin", source.required("origin").asText())
            .put("sha256", hash(bytes)).put("size", bytes.length).put("start", 0).put("end", bytes.length).put("stream", stream)
            .put("nativeSessionId", manifest.required("run").required("session_uid").asText()).putNull("nativeTurnId")
            .putNull("nativeConnectionId").put("clientConnectionId", d.connectionId().orElseThrow());
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
