import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;

/** Installed public Client subprocess contract. Uses a local fixture, never a native model. */
public final class ClientHookContract {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (!Set.of("allow", "deny", "throw", "null", "reject", "eof", "reject-success", "cancel").contains(mode))
            throw new IllegalArgumentException("Unknown fixture scenario");
        Path directory = Path.of(args[1]).toAbsolutePath();
        Files.createDirectory(directory); // Never reuse another scenario's observations.
        Path child = Files.copy(Path.of(args[2]), directory.resolve("child.py"));
        Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        AtomicInteger callbacks = new AtomicInteger();
        var provider = ClaudeClientProvider.builder().workingDirectory(directory).claudePath(child.toString())
            .tools(List.of("Write")).acquisitionDirectory(directory.resolve("capture"), "2.1.316")
            .preToolUse(".*", input -> {
                callbacks.incrementAndGet();
                if (mode.equals("throw")) throw new IllegalStateException("application hook failed");
                if (mode.equals("null")) return null;
                return HookOutput.builder().hookSpecificOutput(mode.equals("allow")
                    ? HookOutput.HookSpecificOutput.preToolUseAllow()
                    : HookOutput.HookSpecificOutput.preToolUseDeny("fixture policy")).build();
            }).build();
        boolean recordingFails = Set.of("reject", "eof", "reject-success", "cancel").contains(mode);
        ExecutionRecorder recorder = decision -> () -> {
            if (recordingFails) throw new IllegalStateException("independent fixture recording failure");
            return "fixture-ack";
        };
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("scenario", mode);
        facts.put("sdkJar", io.github.markpollack.claude.agent.sdk.ClaudeClient.class.getProtectionDomain().getCodeSource().getLocation().toString());
        try (var client = AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(8)).executionRecorder(recorder).build()) {
            var execution = client.goal("hook-" + mode).runAsync();
            if (mode.equals("cancel")) {
                await(() -> Files.exists(directory.resolve("waiting")));
                require(execution.cancel(false), "cancel refused");
            }
            try {
                var response = execution.result();
                facts.put("result", response.getResult());
                facts.put("responseSuccessful", response.isSuccessful());
            } catch (AgentExecutionException | CancellationException failure) {
                facts.put("resultException", failure.getClass().getSimpleName());
            }
            var termination = execution.awaitTermination(Duration.ofSeconds(10));
            facts.put("cleanupConfirmed", termination.cleanupConfirmed());
            facts.put("reconnected", termination.reconnected());
            await(() -> execution.recording().delivery() instanceof TerminalDelivery.Completed
                || execution.recording().delivery() instanceof TerminalDelivery.Failed);
            facts.put("outcome", execution.decision().orElseThrow().outcome().getClass().getSimpleName());
            facts.put("decision", execution.decision().orElseThrow().toString());
            facts.put("recording", execution.recording().toString());
            facts.put("recordingDelivery", execution.recording().delivery().getClass().getSimpleName());
            facts.put("acquisition", execution.recording().evidence().acquisition().name());
        }
        facts.put("callbacks", callbacks.get());
        facts.put("operationExists", Files.exists(directory.resolve("operation")));
        facts.put("launches", Files.readAllLines(directory.resolve("launches.jsonl")).size());
        facts.put("prompts", Files.readAllLines(directory.resolve("prompts.jsonl")).size());
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
                require(!ProcessHandle.of(Long.parseLong(Files.readString(path))).map(ProcessHandle::isAlive).orElse(false), "fixture process survived");
        }
        Files.writeString(directory.resolve("client-observation.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(facts));
        System.out.println(new ObjectMapper().writeValueAsString(facts));
        require(Boolean.TRUE.equals(facts.get("cleanupConfirmed")), "cleanup unknown");
        require(Boolean.FALSE.equals(facts.get("reconnected")), "unexpected reconnect");
        require(facts.get("launches").equals(1) && facts.get("prompts").equals(1), "replayed or replaced");
        require(callbacks.get() == 1, "hook not invoked exactly once");
        require(facts.get("recordingDelivery").equals(recordingFails ? "Failed" : "Completed"), "recording outcome mismatch");
        require(facts.get("acquisition").equals("INCOMPLETE"), "fixture must not claim native completeness");
        switch (mode) {
            case "allow", "deny", "throw", "null" -> {
                require(facts.get("result").equals(mode.equals("allow") ? "ALLOW" : "DENY"), "peer rejected hook wire");
                require(facts.get("operationExists").equals(mode.equals("allow")), "peer operation mismatch");
            }
            case "reject" -> require(facts.get("outcome").equals("Stopped"), "explicit non-success was lost");
            case "eof" -> require(facts.get("outcome").equals("Failed"), "missing terminal did not fail");
            case "cancel" -> require(facts.get("outcome").equals("Cancelled"), "cancellation overwritten");
            case "reject-success" -> {
                require(facts.get("outcome").equals("Success"), "unexpected structured failure inference");
                require(Boolean.TRUE.equals(facts.get("operationExists")), "missing diagnostic-only failure witness");
            }
            default -> throw new AssertionError(mode);
        }
        System.out.println("CLIENT_HOOK_SUBPROCESS_PASS=" + mode);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), "fixture did not reach boundary");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
