import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.ClaudeClientProvider;

/** Installed public directory regression. Child fixture only; never invokes a model. */
public final class ClientDirectoryPrecedence {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (!Set.of("request", "request-async", "goal", "builder", "retained", "provider").contains(mode))
            throw new IllegalArgumentException("Unknown fixture mode");
        Path root = Files.createDirectory(Path.of(args[1]).toAbsolutePath());
        Path a = Files.createDirectory(root.resolve("default-A"));
        Path b = Files.createDirectory(root.resolve("request-B"));
        Path providerHome = Files.createDirectory(root.resolve("provider-home"));
        Path child = Files.copy(Path.of(args[2]), root.resolve("child.py"));
        Files.setPosixFilePermissions(child, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var provider = ClaudeClientProvider.builder().claudePath(child.toString()).workingDirectory(providerHome).build();
        var builder = AgentClient.builder(provider).defaultTimeout(Duration.ofSeconds(5));
        if (!mode.equals("provider")) builder.defaultWorkingDirectory(a);
        Path expected = switch (mode) { case "builder", "retained" -> a; case "provider" -> providerHome; default -> b; };
        try (var client = builder.build()) {
            if (mode.equals("retained")) {
                try (var session = client.sessions().create(s -> s.workingDirectory(a))) {
                    session.goal("cwd-marker:first").run();
                    session.goal("cwd-marker:marker").workingDirectory(b).run();
                }
            } else {
                var request = switch (mode) {
                    case "goal" -> client.goal(new Goal("cwd-marker:marker", b));
                    case "builder" -> client.goal(new Goal("cwd-marker:marker", null, DefaultAgentOptions.builder().model("fixture").build()));
                    case "provider" -> client.goal("cwd-marker:marker");
                    default -> client.goal(new Goal("cwd-marker:marker", root.resolve("unused-goal"))).workingDirectory(b);
                };
                if (mode.equals("request-async")) {
                    var execution = request.runAsync();
                    execution.result(Duration.ofSeconds(8));
                    require(execution.awaitTermination(Duration.ofSeconds(8)).cleanupConfirmed(), "cleanup unknown");
                } else request.run();
            }
        }
        require(Files.exists(expected.resolve("marker")), "child marker absent from expected " + expected);
        require(Files.readString(expected.resolve("marker")).equals(expected.toString()), "actual child cwd differs");
        for (Path candidate : List.of(a,b,providerHome)) {
            if (!candidate.equals(expected)) {
                try (var files = Files.list(candidate)) { require(files.findAny().isEmpty(), "unselected directory touched: " + candidate); }
            }
        }
        require(Files.readAllLines(expected.resolve("launches.jsonl")).size() == 1, "unexpected generation");
        try (var files = Files.list(expected)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("pid-")).toList())
                require(!ProcessHandle.of(Long.parseLong(Files.readString(file))).map(ProcessHandle::isAlive).orElse(false), "child survived");
        }
        System.out.println("DIRECTORY_PRECEDENCE_PASS=" + mode + " cwd=" + expected);
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
