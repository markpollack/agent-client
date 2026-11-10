
/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */
import java.util.function.BooleanSupplier;

final class Await {

	private Await() {
	}

	static void until(BooleanSupplier condition) throws InterruptedException {
		long end = System.nanoTime() + 10_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > end)
				throw new AssertionError("condition not reached in 10 s");
			Thread.sleep(5);
		}
	}

}
