import java.nio.file.Files;
import java.nio.file.Path;
import io.github.markpollack.journal.ocsf.writer.TerminalReceiver;
import io.github.markpollack.journal.ocsf.writer.WriterOptions;

/** Reassemble an unchanged frozen envelope and source package into a fresh destination. */
public final class ReplayNativeArchive {
    public static void main(String[] args) throws Exception {
        Path destination = Path.of(args[2]);
        if (Files.exists(destination)) throw new IllegalArgumentException("Destination must be fresh");
        var receiver = new TerminalReceiver(destination, args[3], WriterOptions.defaults());
        String ack = receiver.receive(Files.readAllBytes(Path.of(args[0])), Path.of(args[1]));
        Files.writeString(destination.resolve("ack.json"), ack);
        System.out.println(ack);
    }
}
