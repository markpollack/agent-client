package io.github.markpollack.agents.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The parts of {@link ClaudeAgentModel#interrupt()} that hold with nothing in flight.
 *
 * <p>
 * The consumer asks specifically whether a cancel arriving when no call is running is
 * safe, because that is a real race: an ACP cancel can land after a run has already
 * finished. It is, and so is cancelling twice.
 *
 * <p>
 * ⚠️ The behaviour that matters most — interrupting a call already blocked inside the
 * client — is proven in {@code ClaudeAgentModelInterruptIT} against a real child process,
 * because this model builds its client internally and there is no seam to substitute a
 * stub one. Adding that seam is a design decision recorded in the steward, not something
 * to introduce for a test.
 */
class ClaudeAgentModelInterruptTest {

	@Test
	void interruptIsSafeWhenNothingIsRunning() {
		try (ClaudeAgentModel model = ClaudeAgentModel.builder().build()) {
			assertThatCode(() -> {
				model.interrupt();
				model.interrupt();
			}).doesNotThrowAnyException();
		}
	}

	@Test
	void closeInterruptsAndStaysIdempotent() {
		ClaudeAgentModel model = ClaudeAgentModel.builder().build();
		assertThatCode(() -> {
			model.close();
			model.close();
		}).doesNotThrowAnyException();
	}

}
