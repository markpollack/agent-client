import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.codex.*;

/**
 * Installed public retained Codex caller: two turns on one session through the public
 * AgentClient/AgentSession API against the synthetic fixture, with recording delivery
 * deliberately held until both turns have completed. It then checks the per-turn
 * manifests and the thread-level manifest written at close. No model is called.
 */
public final class CodexRetained {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path fixture = Path.of(args[1]).toAbsolutePath();
        var sealed = new LinkedBlockingQueue<Map.Entry<String, CodexAcquisition>>();
        var gate = new CountDownLatch(1);
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public java.util.function.Supplier<String> prepare(ExecutionDecision d) { throw new AssertionError(); }
            public java.util.function.Supplier<String> prepare(ExecutionDecision d, Map<String,Object> values) {
                sealed.add(Map.entry(d.executionId(), (CodexAcquisition) values.get("codexAcquisition")));
                return () -> { try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } return "ack"; };
            }
        };
        var provider = CodexClientProvider.builder().codexPath(fixture.toString()).workingDirectory(root)
            .nativeSessionsDirectory(root.resolve("sessions")).acquisitionDirectory(root.resolve("captures"), "0.166.0")
            .skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(root.toString()).model("synthetic-model").effort("low")
            .timeout(Duration.ofSeconds(5)).environmentVariables(Map.of("FIXTURE_SESSIONS", root.resolve("sessions").toString())).build();
        var proof = JSON.createObjectNode();
        proof.put("kind", "client-codex-retained-installed-proof").put("synthetic", true);
        try (var client = AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(root).executionRecorder(recorder).build()) {
            String thread; ExecutionDecision d1, d2; String e1, e2;
            try (var session = client.sessions().create(s -> s.workingDirectory(root).options(options))) {
                require(session.capabilities().retention().supported(), "retention unsupported");
                var first = session.goal("first").runAsync();
                require(first.result().getResult().equals("answer:first"), "wrong first result");
                thread = session.providerReference().orElseThrow().nativeId();
                var second = session.goal("second").runAsync();
                var response = second.result();
                require(response.getResult().equals("answer:second"), "wrong second result");
                require(thread.equals(response.getMetadata().get("resumedThread")), "turn 2 did not resume the emitted thread");
                require(!(first.recording().delivery() instanceof TerminalDelivery.Completed), "turn 1 result waited for delivery");
                require(!(second.recording().delivery() instanceof TerminalDelivery.Completed), "turn 2 result waited for delivery");
                d1 = first.decision().orElseThrow(); d2 = second.decision().orElseThrow();
                e1 = first.executionId(); e2 = second.executionId();
                gate.countDown();
                var a = sealed.poll(10, TimeUnit.SECONDS); var b = sealed.poll(10, TimeUnit.SECONDS);
                require(a != null && b != null, "acquisitions not delivered");
                if (!a.getKey().equals(e1)) { var t = a; a = b; b = t; }
                require(a.getValue().complete(), "turn 1 acquisition: " + a.getValue().detail());
                require(b.getValue().complete(), "turn 2 acquisition: " + b.getValue().detail());
                Path m1 = a.getValue().writeManifest(d1), m2 = b.getValue().writeManifest(d2);
                proof.put("turn1_manifest", m1.toString()).put("turn2_manifest", m2.toString());
                var t1 = JSON.readTree(Files.readAllBytes(m1)).required("turns").get(0);
                var t2 = JSON.readTree(Files.readAllBytes(m2)).required("turns").get(0);
                byte[] n1 = Files.readAllBytes(a.getValue().directory().resolve("native.bin"));
                byte[] n2 = Files.readAllBytes(b.getValue().directory().resolve("native.bin"));
                require(Arrays.equals(Arrays.copyOf(n2, n1.length), n1), "turn 1 snapshot is not a prefix of turn 2");
                require(t2.required("native_prefix_before").required("bytes").asLong() == n1.length, "prefix continuity");
                require(new String(n1, java.nio.charset.StandardCharsets.UTF_8).split("task_started", -1).length == 2, "turn 1 snapshot holds other turns");
                require(second.awaitTermination(Duration.ofSeconds(5)).cleanupConfirmed(), "cleanup unconfirmed");
            }
            Path threadManifest;
            try (var s = Files.list(root.resolve("captures"))) {
                threadManifest = s.filter(p -> p.getFileName().toString().startsWith("codex-thread-")).map(p -> p.resolve("codex-thread.json")).findFirst().orElseThrow();
            }
            var manifest = JSON.readTree(Files.readAllBytes(threadManifest));
            require(manifest.required("kind").asText().equals("journal-codex-retained-generation/1"), "thread manifest kind");
            require(manifest.required("native_session_id").asText().equals(thread), "thread manifest identity");
            byte[] transcript = Files.readAllBytes(threadManifest.resolveSibling("transcript.bin"));
            require(manifest.required("transcript").required("sha256").asText().equals(sha256(transcript)), "transcript hash");
            require(manifest.required("transcript").required("final_equals_last_sealed_prefix").asBoolean(), "final transcript differs from last sealed prefix");
            var turns = manifest.required("turns");
            require(turns.size() == 2, "turn count");
            long covered = turns.get(0).required("native_records").required("shared").get(0).required("end").asLong();
            for (var turn : turns) for (var r : turn.required("native_records").required("records")) {
                require(r.required("start").asLong() == covered, "locator gap/overlap at " + covered);
                long end = r.required("end").asLong();
                require(sha256(Arrays.copyOfRange(transcript, (int) covered, (int) end)).equals(r.required("sha256").asText()), "locator hash");
                covered = end;
            }
            require(covered == transcript.length, "locators do not cover the transcript");
            int total1 = turns.get(0).required("thread_token_usage").required("total_tokens").asInt();
            int total2 = turns.get(1).required("thread_token_usage").required("total_tokens").asInt();
            int turn2 = turns.get(1).required("turn_token_usage").required("total_tokens").asInt();
            require(total2 - total1 == turn2, "thread(2) - thread(1) != turn(2)");
            proof.put("thread_manifest", threadManifest.toString()).put("thread_manifest_sha256", sha256(Files.readAllBytes(threadManifest)))
                 .put("transcript_sha256", sha256(transcript)).put("transcript_bytes", transcript.length).put("native_session_id", thread)
                 .put("launches", Files.readAllLines(root.resolve("launches.jsonl")).size());
        }
        Files.write(root.resolve("proof.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(proof), StandardOpenOption.CREATE_NEW);
        System.out.println("PASS " + root.resolve("proof.json"));
    }
    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
