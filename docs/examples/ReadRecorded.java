import java.nio.file.Path;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.Diagnostic;

/** Compile/run with Journal's independent reader JAR and its runtime closure only. */
public final class ReadRecorded {
 public static void main(String[] args) throws Exception {
  for (String forbidden : new String[]{"io.github.markpollack.agents.client.AgentClient", "io.github.markpollack.journal.ocsf.writer.TerminalReceiver"}) {
   try { Class.forName(forbidden); throw new AssertionError("Unexpected classpath entry: "+forbidden); }
   catch (ClassNotFoundException expected) { }
  }
  var pkg=PackageReader.open(Path.of(args[0]));
  if (pkg.diagnostics.stream().anyMatch(d->d.severity()==Diagnostic.Severity.DEFECT || d.severity()==Diagnostic.Severity.REFUSED)) throw new AssertionError(pkg.diagnostics);
  var binding=TerminalBindingReader.read(pkg.declarations);
  if (!binding.decision().contains("\"decisionId\":\""+args[1]+"\"")) throw new AssertionError("Decision identity mismatch");
  String expectedKind = args.length > 2 ? args[2] : "SUCCESS";
  if (!binding.decision().contains("\"kind\":\""+expectedKind+"\"")) throw new AssertionError("Unexpected outcome");
  if (!binding.association().contains("\"acquisition\":\"UNAVAILABLE\"")) throw new AssertionError("Native evidence overstated");
  System.out.println("INDEPENDENT_INSTALLED_READER_PASS decision="+args[1]+" native=UNAVAILABLE");
 }
}
