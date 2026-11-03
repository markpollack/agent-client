import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.tck.ocsf.*;

/** Independent TCK-only reader for two decision-only packages; not retained archive qualification. */
public final class ReadRetainedDecisions {
    public static void main(String[] args) throws Exception {
        for (String forbidden : List.of("io.github.markpollack.agents.client.AgentClient", "io.github.markpollack.journal.ocsf.writer.TerminalReceiver")) {
            try { Class.forName(forbidden); throw new AssertionError("Unexpected dependency: " + forbidden); }
            catch (ClassNotFoundException expected) { }
        }
        var json = new ObjectMapper();
        Path root = Path.of(args[0]);
        var proof = json.readTree(root.resolve("proof.json").toFile());
        var expected = new HashSet<String>();
        proof.path("decisionIds").forEach(id -> expected.add(id.asText()));
        var found = new HashSet<String>();
        try (var paths = Files.walk(root.resolve("journal/prepared"))) {
            for (Path events : paths.filter(p -> p.getFileName().toString().equals("events.jsonl")).toList()) {
                var pkg = PackageReader.open(events);
                if (pkg.diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED))
                    throw new AssertionError(pkg.diagnostics);
                var binding = TerminalBindingReader.read(pkg.declarations);
                var d = json.readTree(binding.decision());
                String id = d.path("decisionId").asText();
                if (!expected.contains(id) || !d.path("connectionId").asText().equals(proof.path("connectionId").asText()))
                    throw new AssertionError("Wrong original D/generation");
                int index = proof.path("decisionIds").get(0).asText().equals(id) ? 0 : 1;
                if (!d.path("executionId").asText().equals(proof.path("executionIds").get(index).asText())
                    || !d.path("sessionId").asText().equals(proof.path("logicalSessionId").asText()))
                    throw new AssertionError("Wrong execution/logical session");
                if (binding.association().contains("\"acquisition\":\"COMPLETED\"")) throw new AssertionError("Invented archive");
                found.add(id);
                // Persist exact immutable D read from the installed writer for later same-D revision qualification.
                Files.writeString(root.resolve(id + ".decision.json"), binding.decision(), StandardOpenOption.CREATE_NEW);
            }
        }
        if (!found.equals(expected) || found.size() != 2) throw new AssertionError("Missing decision");
        System.out.println("INDEPENDENT_TWO_DECISIONS_PASS; retained attachments NOT qualified");
    }
}
