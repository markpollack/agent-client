import java.nio.file.Path;
import io.github.markpollack.journal.tck.ocsf.*;

/** Compile/run with only Journal's reader JAR and its runtime dependencies. */
public final class ReadNativeClient {
    public static void main(String[] args) throws Exception {
        for (String forbidden : new String[]{"io.github.markpollack.agents.client.AgentClient", "io.github.markpollack.journal.ocsf.writer.TerminalReceiver"}) {
            try { Class.forName(forbidden); throw new AssertionError("Unexpected reader dependency: " + forbidden); }
            catch (ClassNotFoundException expected) { }
        }
        var pkg = PackageReader.open(Path.of(args[0]));
        if (pkg.diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED))
            throw new AssertionError(pkg.diagnostics);
        var binding = TerminalBindingReader.read(pkg.declarations);
        if (!binding.decision().contains("\"decisionId\":\"" + args[1] + "\"")) throw new AssertionError("Decision mismatch");
        if (args.length > 4 && !binding.decision().equals(java.nio.file.Files.readString(Path.of(args[4]))))
            throw new AssertionError("Frozen decision bytes differ");
        if (!binding.association().contains("\"acquisition\":\"COMPLETED\"")) throw new AssertionError("Acquisition incomplete");
        int exit = OcsfReaderTool.run(new String[]{"verify", args[0], args[2], "--acquisition", args[2] + "/acquisition.json", "--assembly", args[3]}, System.out, System.err);
        if (exit != 0) throw new AssertionError("Complete archive verification failed: " + exit);
        System.out.println("INDEPENDENT_NATIVE_READER_PASS decision=" + args[1]);
    }
}
