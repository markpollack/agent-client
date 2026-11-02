/*
 * Copyright (c) 2024-2026 Mark Pollack
 * See LICENSE in the repository root for project-specific Business Source License terms.
 */

package io.github.markpollack.agents.client;

import java.util.concurrent.*;
import java.util.function.*;

/** Isolates implicit dependent execution, including registration after completion. */
final class IsolatedStage<T> implements CompletionStage<T> {

	private final CompletableFuture<T> value;

	private final Executor executor;

	IsolatedStage(CompletableFuture<T> value, Executor executor) {
		this.value = value;
		this.executor = executor;
	}

	@Override
	public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> p0) {
		return new IsolatedStage<>(this.value.thenApplyAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> p0) {
		return new IsolatedStage<>(this.value.thenApplyAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> p0, Executor p1) {
		return new IsolatedStage<>(this.value.thenApplyAsync(p0, p1), this.executor);
	}

	@Override
	public CompletionStage<Void> thenAccept(Consumer<? super T> p0) {
		return new IsolatedStage<>(this.value.thenAcceptAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> p0) {
		return new IsolatedStage<>(this.value.thenAcceptAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> p0, Executor p1) {
		return new IsolatedStage<>(this.value.thenAcceptAsync(p0, p1), this.executor);
	}

	@Override
	public CompletionStage<Void> thenRun(Runnable p0) {
		return new IsolatedStage<>(this.value.thenRunAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> thenRunAsync(Runnable p0) {
		return new IsolatedStage<>(this.value.thenRunAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> thenRunAsync(Runnable p0, Executor p1) {
		return new IsolatedStage<>(this.value.thenRunAsync(p0, p1), this.executor);
	}

	@Override
	public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> p0,
			BiFunction<? super T, ? super U, ? extends V> p1) {
		return new IsolatedStage<>(this.value.thenCombineAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> p0,
			BiFunction<? super T, ? super U, ? extends V> p1) {
		return new IsolatedStage<>(this.value.thenCombineAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> p0,
			BiFunction<? super T, ? super U, ? extends V> p1, Executor p2) {
		return new IsolatedStage<>(this.value.thenCombineAsync(p0, p1, p2), this.executor);
	}

	@Override
	public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> p0,
			BiConsumer<? super T, ? super U> p1) {
		return new IsolatedStage<>(this.value.thenAcceptBothAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> p0,
			BiConsumer<? super T, ? super U> p1) {
		return new IsolatedStage<>(this.value.thenAcceptBothAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> p0,
			BiConsumer<? super T, ? super U> p1, Executor p2) {
		return new IsolatedStage<>(this.value.thenAcceptBothAsync(p0, p1, p2), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterBoth(CompletionStage<?> p0, Runnable p1) {
		return new IsolatedStage<>(this.value.runAfterBothAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> p0, Runnable p1) {
		return new IsolatedStage<>(this.value.runAfterBothAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> p0, Runnable p1, Executor p2) {
		return new IsolatedStage<>(this.value.runAfterBothAsync(p0, p1, p2), this.executor);
	}

	@Override
	public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> p0, Function<? super T, U> p1) {
		return new IsolatedStage<>(this.value.applyToEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> p0, Function<? super T, U> p1) {
		return new IsolatedStage<>(this.value.applyToEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> p0, Function<? super T, U> p1,
			Executor p2) {
		return new IsolatedStage<>(this.value.applyToEitherAsync(p0, p1, p2), this.executor);
	}

	@Override
	public CompletionStage<Void> acceptEither(CompletionStage<? extends T> p0, Consumer<? super T> p1) {
		return new IsolatedStage<>(this.value.acceptEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> p0, Consumer<? super T> p1) {
		return new IsolatedStage<>(this.value.acceptEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> p0, Consumer<? super T> p1,
			Executor p2) {
		return new IsolatedStage<>(this.value.acceptEitherAsync(p0, p1, p2), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterEither(CompletionStage<?> p0, Runnable p1) {
		return new IsolatedStage<>(this.value.runAfterEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> p0, Runnable p1) {
		return new IsolatedStage<>(this.value.runAfterEitherAsync(p0, p1, this.executor), this.executor);
	}

	@Override
	public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> p0, Runnable p1, Executor p2) {
		return new IsolatedStage<>(this.value.runAfterEitherAsync(p0, p1, p2), this.executor);
	}

	@Override
	public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> p0) {
		return new IsolatedStage<>(this.value.thenComposeAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> p0) {
		return new IsolatedStage<>(this.value.thenComposeAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> p0, Executor p1) {
		return new IsolatedStage<>(this.value.thenComposeAsync(p0, p1), this.executor);
	}

	@Override
	public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> p0) {
		return new IsolatedStage<>(this.value.handleAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> p0) {
		return new IsolatedStage<>(this.value.handleAsync(p0, this.executor), this.executor);
	}

	@Override
	public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> p0, Executor p1) {
		return new IsolatedStage<>(this.value.handleAsync(p0, p1), this.executor);
	}

	@Override
	public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> p0) {
		return new IsolatedStage<>(this.value.whenCompleteAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> p0) {
		return new IsolatedStage<>(this.value.whenCompleteAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> p0, Executor p1) {
		return new IsolatedStage<>(this.value.whenCompleteAsync(p0, p1), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> p0) {
		return new IsolatedStage<>(this.value.exceptionallyAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> p0) {
		return new IsolatedStage<>(this.value.exceptionallyAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> p0, Executor p1) {
		return new IsolatedStage<>(this.value.exceptionallyAsync(p0, p1), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> p0) {
		return new IsolatedStage<>(this.value.exceptionallyComposeAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> p0) {
		return new IsolatedStage<>(this.value.exceptionallyComposeAsync(p0, this.executor), this.executor);
	}

	@Override
	public CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> p0,
			Executor p1) {
		return new IsolatedStage<>(this.value.exceptionallyComposeAsync(p0, p1), this.executor);
	}

	@Override
	public CompletableFuture<T> toCompletableFuture() {
		return this.value.copy();
	}

}
