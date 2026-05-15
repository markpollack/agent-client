///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//REPOS central=https://repo1.maven.org/maven2/
//REPOS central-snapshots=https://central.sonatype.com/repository/maven-snapshots/
//DEPS io.github.markpollack.agents:spring-ai-agents-core:0.1.0-SNAPSHOT
//DEPS io.github.markpollack.agents:hello-world-agent:0.1.0-SNAPSHOT
//DEPS io.github.markpollack.agents:hello-world-agent-ai:0.1.0-SNAPSHOT
//DEPS io.github.markpollack.agents:code-coverage-agent:0.1.0-SNAPSHOT

import io.github.markpollack.agents.core.*;

public class launcher {
    public static void main(String[] argv) throws Exception {
        LauncherSpec spec = LocalConfigLoader.load(argv);
        Result r = Launcher.execute(spec);
        if (!r.success()) System.exit(1);
        System.out.println(r.message());
    }
}