import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.journal.tck.ocsf.*;

/** Separate JVM: only Journal's reader JAR and runtime closure, never Client/writer classes. */
public final class ReadCodexArchive {
    public static void main(String[] args) throws Exception {
        for (String forbidden : List.of("io.github.markpollack.agents.client.AgentClient",
                "io.github.markpollack.agents.codexsdk.transport.CodexProcess", "io.github.markpollack.journal.ocsf.writer.TerminalReceiver")) {
            try { Class.forName(forbidden); throw new AssertionError("Unexpected dependency: " + forbidden); }
            catch (ClassNotFoundException expected) { }
        }
        var json = new ObjectMapper(); var proof = json.readTree(Files.readAllBytes(Path.of(args[0])));
        Path events = Path.of(proof.required("events").asText());
        Path sources = args.length > 1 ? Path.of(args[1]) : events.getParent().resolve("sources");
        var pkg = PackageReader.open(events);
        if (pkg.diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED)) throw new AssertionError(pkg.diagnostics);
        var binding = TerminalBindingReader.read(pkg.declarations);
        require(binding.decision().equals(Files.readString(Path.of(proof.required("originalDecision").asText()))), "original D changed");
        var d = json.readTree(binding.decision()); var a = json.readTree(binding.association());
        for (String key : List.of("executionId", "decisionId", "sessionId", "connectionId")) require(d.required(key).equals(proof.required(key)), "wrong " + key);
        require(a.required("acquisition").asText().equals("COMPLETED"), "incomplete acquisition");
        require(a.required("executionId").equals(d.required("executionId")), "wrong association E");
        require(a.required("decisionId").equals(d.required("decisionId")), "wrong association D");
        for (var source : a.required("sources")) {
            require(source.required("nativeSessionId").equals(proof.required("nativeSessionId")), "wrong thread");
            require(source.required("clientConnectionId").equals(proof.required("connectionId")), "wrong connection");
            require(source.required("nativeTurnId").isNull(), "whole source incorrectly narrowed to turn");
        }
        Path sealed = Path.of(proof.required("sealedManifest").asText());
        var original = json.readTree(Files.readAllBytes(sealed));
        var acquisition = json.readTree(Files.readAllBytes(sources.resolve("acquisition.json")));
        require(acquisition.required("run").required("session_uid").equals(proof.required("nativeSessionId")), "mismatched capture thread");
        require(acquisition.required("turns").get(0).required("native_turn_uid").equals(proof.required("nativeTurnId")), "wrong native turn");
        for (var source : original.required("sources")) {
            String path = source.required("path").asText();
            require(Arrays.equals(Files.readAllBytes(sealed.getParent().resolve(path)), Files.readAllBytes(sources.resolve(path))), "original bytes changed: " + path);
        }
        int exit = OcsfReaderTool.run(new String[]{"verify", events.toString(), sources.toString(), "--acquisition",
            sources.resolve("acquisition.json").toString(), "--assembly", proof.required("assembly").asText()}, System.out, System.err);
        require(exit == 0, "independent archive rejection: " + exit);
        System.out.println("INDEPENDENT_CODEX_READER_PASS E=" + d.required("executionId") + " D=" + d.required("decisionId"));
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
