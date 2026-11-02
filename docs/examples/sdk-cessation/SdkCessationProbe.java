import java.nio.file.*;
import java.time.Duration;
import io.github.markpollack.claude.agent.sdk.ClaudeClient;
import io.github.markpollack.claude.agent.sdk.transport.CLIOptions;

/** Fake child only: no inference or prompt; run against the selected SDK JAR. */
public final class SdkCessationProbe {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]);
  var client=ClaudeClient.sync(CLIOptions.builder().build()).workingDirectory(root)
    .timeout(Duration.ofSeconds(5)).claudePath(root.resolve("child.py").toString()).build();
  ProcessHandle descendant=null;
  try {
   System.out.println("SDK_JAR="+ClaudeClient.class.getProtectionDomain().getCodeSource().getLocation());
   client.connect();
   long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
   while (!Files.exists(root.resolve("descendant.pid")) && System.nanoTime()<deadline) Thread.sleep(10);
   descendant=ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("descendant.pid")))).orElseThrow();
   client.close();
   boolean rootAlive=ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("root.pid")))).map(ProcessHandle::isAlive).orElse(false);
   System.out.println("SDK_CLOSE_RETURNED rootAlive="+rootAlive+" descendantAlive="+descendant.isAlive());
   if (rootAlive || descendant.isAlive()) throw new AssertionError("Owned process survived close");
   var resources=client.transportResources().orElseThrow();
   if (!resources.observedResourcesTerminated()) throw new AssertionError("Observed resources remain unsettled: "+resources);
   System.out.println("OBSERVED_RESOURCE_CESSATION_CONFIRMED=true");
  } finally {
   if (descendant!=null && descendant.isAlive()) { descendant.destroyForcibly(); descendant.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS); }
   client.close();
   System.out.println("OWNED_DESCENDANT_CLEANED="+(descendant==null || !descendant.isAlive()));
  }
 }
}
