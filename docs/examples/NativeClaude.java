import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.claude.agent.sdk.types.control.HookInput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;

/** Installed-JAR acceptance example. Each mode submits a fixed, printed number of real tasks. */
public final class NativeClaude {
    static final Duration LIMIT = Duration.ofMinutes(4);
    static Path output;
    static void record(String label, AgentExecution execution) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed)
                && !(execution.recording().delivery() instanceof TerminalDelivery.Failed)
                && System.nanoTime() < until) Thread.sleep(10);
        var recording = execution.recording();
        Files.writeString(output.resolve(label + ".txt"), "execution=" + execution.executionId()
            + "\nsession=" + execution.sessionId() + "\nconnection=" + execution.decision().orElseThrow().connectionId().orElse("")
            + "\ndecision=" + execution.decision().orElseThrow().decisionId()
            + "\noutcome=" + execution.decision().orElseThrow().outcome()
            + "\ncorrelation=" + execution.decision().orElseThrow().correlation()
            + "\nrecording=" + recording + "\n");
        System.out.println(label + " " + execution.executionId() + " " + recording.delivery());
        if (!(recording.delivery() instanceof TerminalDelivery.Completed)) throw new AssertionError(recording.toString());
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0]; Path workspace = Path.of(args[1]).toAbsolutePath(); output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);
        var provider = ClaudeClientProvider.builder().workingDirectory(workspace).maxBudgetUsd(1.0)
            .tools(List.of("Read", "Bash"))
            .preToolUse(".*", input -> {
                boolean allowed = false;
                if (input instanceof HookInput.PreToolUseInput tool) {
                    if (tool.toolName().equals("Read")) {
                        String file = tool.getArgument("file_path", String.class).orElse("");
                        allowed = workspace.resolve(file).normalize().equals(workspace.resolve("sentinel.txt"));
                    }
                    if (tool.toolName().equals("Bash"))
                        allowed = tool.getArgument("command", String.class).orElse("").equals("printf started > cancel-started; sleep 30");
                    System.out.println("PRE_TOOL " + tool.toolName() + " allowed=" + allowed);
                }
                return HookOutput.builder().hookSpecificOutput(allowed
                    ? HookOutput.HookSpecificOutput.preToolUseAllow("Bounded acceptance action")
                    : HookOutput.HookSpecificOutput.preToolUseDeny("Outside acceptance actions")).build();
            })
            .acquisitionDirectory(output.resolve("sources"), "2.1.316").build();
        var recorder = JournalExecutionRecorder.claude(output.resolve("journal"), "native-client-acceptance");
        try (var client = AgentClient.builder(provider).defaultTimeout(LIMIT).executionRecorder(recorder).build()) {
            if (mode.equals("one-shot")) {
                System.out.println("SUBMISSION 1 one-shot");
                var execution = client.goal("Read sentinel.txt using the Read tool. Reply with its exact content. Do not edit files or use Bash.")
                    .correlation(new ApplicationCorrelation(Optional.of("native-acceptance"), Optional.of("one-shot"),
                        Optional.of(UUID.randomUUID().toString()), Optional.empty(), Optional.empty(), Optional.empty()))
                    .executionTimeout(LIMIT).runAsync();
                var response = execution.result();
                System.out.println("RESULT " + response.getResult());
                if (!response.isSuccessful() || !response.getResult().contains("CLIENT_NATIVE_SENTINEL")) throw new AssertionError(response);
                if (!execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()) throw new AssertionError("cleanup unknown");
                record("one-shot", execution);
                if (execution.recording().evidence().acquisition() != CaptureAcquisition.COMPLETED) throw new AssertionError("source acquisition incomplete");
            } else if (mode.equals("retained")) {
                try (var session = client.sessions().create(s -> s.workingDirectory(workspace))) {
                    System.out.println("SUBMISSION 1 retained-first");
                    var first = session.goal("Remember the exact token OAK-742 for this conversation. Reply only ACK. Do not use tools.").executionTimeout(LIMIT).runAsync();
                    if (!first.result().isSuccessful()) throw new AssertionError("first failed"); record("first", first);
                    System.out.println("SUBMISSION 2 retained-followup");
                    var second = session.goal("What exact token did I ask you to remember? Reply only that token. Do not use tools.").executionTimeout(LIMIT).runAsync();
                    if (!second.result().getResult().contains("OAK-742")) throw new AssertionError("history missing"); record("second", second);
                    String history = session.providerReference().orElseThrow().nativeId();
                    System.out.println("SUBMISSION 3 targeted-cancel");
                    var cancelled = session.goal("Use Bash once to run: printf started > cancel-started; sleep 30. Wait for that command, then reply DONE. Do not run anything else.")
                        .executionTimeout(LIMIT).runAsync();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(210);
                    while (!Files.exists(workspace.resolve("cancel-started")) && !cancelled.isDone() && System.nanoTime() < until) Thread.sleep(20);
                    boolean reached = Files.exists(workspace.resolve("cancel-started"));
                    boolean accepted = cancelled.cancel(false);
                    var termination = cancelled.awaitTermination(Duration.ofSeconds(12));
                    if (!reached || !accepted || !termination.cleanupConfirmed()) throw new AssertionError("cancel proof: marker=" + reached + " accepted=" + accepted + " " + termination);
                    record("cancelled", cancelled);
                    System.out.println("SUBMISSION 4 after-cancel");
                    var after = session.goal("What exact token did I ask you to remember earlier? Reply only that token. Do not use tools.").executionTimeout(LIMIT).runAsync();
                    if (!after.result().getResult().contains("OAK-742")) throw new AssertionError("reconnect lost history");
                    if (!after.inspect().reconnected() || !history.equals(session.providerReference().orElseThrow().nativeId())) throw new AssertionError("wrong reconnect");
                    record("after", after);
                }
            } else throw new IllegalArgumentException("one-shot or retained");
        }
        System.out.println("CLIENT_CLOSED_OBSERVED_RESOURCES=true");
    }
}
