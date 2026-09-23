/*
 * Copyright (c) 2025-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
package io.github.markpollack.agents.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.markpollack.agents.model.AgentSessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies context delivery through the real SDK without invoking a model. */
@EnabledOnOs(OS.LINUX)
class ClaudeConversationContextTest {

	@TempDir
	Path directory;

	@Test
	void unscopedConversationDeliversFreshContextAcrossTurnsAndResumeWithoutReplay() throws Exception {
		Path executable = directory.resolve("fake-claude");
		Files.writeString(executable,
				"""
						#!/usr/bin/python3
						import json, sys
						args = sys.argv[1:]
						phase = 'resumed' if '--resume' in args else 'initial'
						identity = args[args.index('--resume' if phase == 'resumed' else '--session-id') + 1]
						with open(phase + '-argv.json', 'w') as output:
						    json.dump(args, output)
						def emit(value):
						    print(json.dumps(value), flush=True)
						emit({'type':'system','subtype':'init'})
						for line in sys.stdin:
						    prompt = json.loads(line)['message']['content']
						    with open(phase + '-prompts.jsonl', 'a') as output:
						        output.write(json.dumps(prompt) + '\\n')
						    emit({'type':'assistant','message':{'role':'assistant','content':[{'type':'text','text':'context received'}]}})
						    if not prompt.startswith('WAIT'):
						        emit({'type':'result','subtype':'success','is_error':False,'session_id':identity,'result':'context received'})
						""");
		assertThat(executable.toFile().setExecutable(true)).isTrue();
		var registry = ClaudeAgentSessionRegistry.builder().claudePath(executable.toString()).build();
		String first = "Bundle: /bundle/recipes. Connection: /private/turn-1/curl.conf. Request status.";
		String second = "Bundle: /bundle/recipes. Connection: /private/turn-2/curl.conf. Inspect run-17.";
		String cancelled = "WAIT Connection: /private/turn-3/curl.conf.";
		String resumed = "Connection: /private/turn-4/curl.conf. Retained outcome: run-17 failed, nonce-929.";
		try (var session = registry.create(directory); var executor = Executors.newSingleThreadExecutor()) {
			String identity = session.getSessionId();
			assertThat(session.prompt(first).getText()).contains("context received");
			assertThat(session.prompt(second).getText()).contains("context received");
			var entered = new CountDownLatch(1);
			var pending = executor.submit(() -> session.prompt(cancelled, event -> {
				if (event instanceof AgentSessionEvent.Text) {
					entered.countDown();
				}
			}));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			session.cancelActiveTurn();
			assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
			session.resume();
			assertThat(session.prompt(resumed).getText()).contains("context received");
			assertThat(session.getSessionId()).isEqualTo(identity);
			var mapper = new ObjectMapper();
			for (String phase : List.of("initial", "resumed")) {
				assertThat(Files.readString(directory.resolve(phase + "-argv.json"))).doesNotContain("--mcp-config",
						"--strict-mcp-config");
			}
			var initialPrompts = Files.readAllLines(directory.resolve("initial-prompts.jsonl"));
			assertThat(initialPrompts).containsExactly(mapper.writeValueAsString(first),
					mapper.writeValueAsString(second), mapper.writeValueAsString(cancelled));
			assertThat(Files.readAllLines(directory.resolve("resumed-prompts.jsonl")))
				.containsExactly(mapper.writeValueAsString(resumed));
			assertThat(Files.readString(directory.resolve("resumed-argv.json"))).contains(identity, "--resume")
				.doesNotContain("--session-id");
		}
	}

}
