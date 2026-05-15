# Agent Client

[![Maven Central](https://img.shields.io/maven-central/v/io.github.markpollack/agent-starter-claude.svg)](https://search.maven.org/search?q=g:io.github.markpollack)

**What ChatClient did for completion endpoints, AgentClient does for agent CLIs.**

Agent Client provides a unified Java API for autonomous CLI agents — Claude Code, Codex, Gemini, Amazon Q, and Amp — with Spring Boot auto-configuration support.

📖 **[Documentation](https://lab.pollack.ai/projects/incubating/agent-client)** | [Getting Started](https://lab.pollack.ai/agent-client/howto/getting-started) | [Reference](https://lab.pollack.ai/agent-client/reference/portable-options) | [Tutorial](https://lab.pollack.ai/agent-client/tutorial/index)

## Quick Start

Add the dependency for your provider:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>agent-claude</artifactId>
    <version>0.18.0</version>
</dependency>
```

Build a model, create a client, run a goal — no Spring Boot required:

```java
ClaudeAgentModel model = ClaudeAgentModel.builder()
    .defaultOptions(ClaudeAgentOptions.builder()
        .model("claude-sonnet-4-5")
        .yolo(true)
        .build())
    .build();

AgentClient client = AgentClient.create(model);
AgentClientResponse response = client.run("Create hello.txt with 'Hello from Agent Client!'");
```

### With Spring Boot

Use a starter for auto-configuration:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>agent-starter-claude</artifactId>
    <version>0.18.0</version>
</dependency>
```

```java
@Component
public class MyAgent implements CommandLineRunner {
    private final AgentClient.Builder agentClientBuilder;

    public MyAgent(AgentClient.Builder agentClientBuilder) {
        this.agentClientBuilder = agentClientBuilder;
    }

    @Override
    public void run(String... args) {
        AgentClient client = agentClientBuilder.build();
        AgentClientResponse response = client.run("Fix the failing test");
    }
}
```

## Supported Providers

| Provider | Starter | Status |
|----------|---------|--------|
| [Claude Code](https://docs.anthropic.com/en/docs/claude-code) | `agent-starter-claude` | Production |
| [Codex](https://github.com/openai/codex) | `agent-starter-codex` | Production |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) | `agent-starter-gemini` | Production |
| [Amazon Q](https://aws.amazon.com/q/developer/) | `agent-starter-amazon-q` | Beta |
| [Amp](https://ampcode.com/) | `agent-starter-amp` | Beta |

## Multi-Provider Support

Switch providers without changing code — use Maven profiles or swap the starter:

```java
// This code works with ANY provider
AgentClient client = AgentClient.create(model);
AgentClientResponse response = client.run("Create hello.txt");
```

See [Switching Providers](https://lab.pollack.ai/agent-client/howto/switching-providers) for the Maven profile pattern.

## Configuration

```yaml
spring:
  ai:
    agents:
      mode: loose  # or strict
      claude-code:
        model: claude-sonnet-4-5
        timeout: PT5M
        yolo: true
      codex:
        model: gpt-5-codex
        full-auto: true
      gemini:
        model: gemini-2.5-flash
        yolo: true
```

See the [Reference](https://lab.pollack.ai/agent-client/reference/portable-options) pages for all configuration options.

## Architecture

```
agent-client/
├── agent-client-core/               # AgentClient fluent API
├── agent-models/                    # Provider adapters
│   ├── agent-model/                 # Core abstractions (AgentModel, AgentOptions)
│   ├── agent-tck/                   # Provider parity test kit
│   ├── agent-claude/                # Claude Code adapter
│   ├── agent-codex/                 # Codex adapter
│   ├── agent-gemini/                # Gemini CLI adapter
│   ├── agent-amazon-q/              # Amazon Q adapter
│   └── agent-amp/                   # Amp adapter
├── provider-sdks/                   # CLI client libraries
├── agent-starters/                  # Spring Boot auto-configuration
└── agents/                          # JBang-compatible agents
```

### Two-Layer Design

- **`AgentClient`** — High-level fluent API (like `ChatClient`)
- **`AgentModel`** — Low-level provider interface (like `ChatModel`)

Provider selection happens at construction time. Everything after `AgentClient.create(model)` is portable.

## Documentation

| Type | Link |
|------|------|
| Getting Started | [Quick start guide](https://lab.pollack.ai/agent-client/howto/getting-started) |
| Tutorial | [Step-by-step lessons](https://lab.pollack.ai/agent-client/tutorial/index) |
| Reference | [Configuration options](https://lab.pollack.ai/agent-client/reference/portable-options) |
| Provider Reference | [Claude](https://lab.pollack.ai/agent-client/reference/claude-reference) · [Codex](https://lab.pollack.ai/agent-client/reference/codex-reference) · [Gemini](https://lab.pollack.ai/agent-client/reference/gemini-reference) |
| Defaults Philosophy | [LOOSE vs STRICT modes](https://lab.pollack.ai/agent-client/explanation/defaults-philosophy) |
| Sessions | [Multi-turn conversations](https://lab.pollack.ai/agent-client/reference/sessions) |

## Building

```bash
./mvnw clean compile          # Compile
./mvnw clean test             # Unit tests
./mvnw clean verify -Pfailsafe  # Integration tests (requires CLIs + API keys)
```

## Relationship to Agent Judge

AgentClient runs CLI-delegated agents. [Agent Judge](https://github.com/markpollack/agent-judge) evaluates their outputs. The `agent-judge-bridge` module provides the canonical bridge from AgentClient responses into Agent Judge's `JudgmentContext`.

## Licensing

This project originated from earlier Apache-licensed work in the Spring AI Community.

Beginning with version 0.18.0, new development is licensed under the Business Source License 1.1 (BSL).

Historical Apache-licensed portions remain available under their original terms. See [LICENSE](LICENSE) and [LICENSE-APACHE.txt](LICENSE-APACHE.txt) for details.

## Migration Notes

This project was previously published under `org.springaicommunity.agents`. Beginning with version 0.18.0, the Maven coordinates are:

```xml
<groupId>io.github.markpollack</groupId>
```

All Java packages have moved from `org.springaicommunity.agents.*` to `io.github.markpollack.agents.*`.
