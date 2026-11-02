import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.model.*;

/** Complete deterministic public Client caller, including independent recording. */
public final class RecordedAsync {
 public static void main(String[] args) throws Exception {
  AgentExecutionProvider provider=options -> new AgentExecutionProvider.Connection() {
   @Override public AgentExecutionProvider.Turn prepare(String id) {
    return new AgentExecutionProvider.Turn() {
     private volatile boolean stopped;
     @Override public AgentResponse call(AgentTaskRequest request) {
      if (stopped) throw new CancellationException();
      return new AgentResponse(List.of(new AgentGeneration(request.goal(),new AgentGenerationMetadata("SUCCESS",Map.of()))));
     }
     @Override public void stop(boolean interrupt) { stopped=true; }
    };
   }
   @Override public void close() { }
  };
  var recorder=new JournalExecutionRecorder(Path.of(args[0]),"installed-client-example");
  try (var client=AgentClient.builder(provider).executionRecorder(recorder).build();
       io.github.markpollack.agents.client.AgentSession session=client.sessions().create(spec -> {})) {
   var first=session.goal("first retained turn").executionTimeout(Duration.ofSeconds(5)).runAsync();
   System.out.println(first.result().getResult());
   var next=session.goal("follow-up retained turn").executionTimeout(Duration.ofSeconds(5)).runAsync();
   System.out.println(next.result().getResult());
   for (var execution:List.of(first,next)) {
    long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    while (!(execution.recording().delivery() instanceof TerminalDelivery.Completed) && System.nanoTime()<end) Thread.sleep(10);
    if (!(execution.recording().delivery() instanceof TerminalDelivery.Completed)) throw new AssertionError(execution.recording());
    System.out.println("RECORDED execution="+execution.executionId()+" decision="+execution.decision().orElseThrow().decisionId());
   }
  }
 }
}
