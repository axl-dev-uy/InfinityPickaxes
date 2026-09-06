package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.integration.IntegrationTasks;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

/** Internal asynchronous outbox transport. Not registered until a durable consumer and XP recovery exist.
 * Delivery is at least once: receivers persist/deduplicate creditId before confirming acceptance.
 * No XP callback exists here; replay can never reapply mining XP.
 */
public final class MiningNotificationDispatcher implements AutoCloseable {
    public interface Outbox {
        List<MiningCredit> pendingNotifications(int limit);
        void notificationDelivered(UUID creditId);
    }
    @FunctionalInterface public interface Receiver {
        /** Invoked on the server thread; must return promptly. True means durable acceptance,
         * including an already-persisted duplicate. False defers. Event dispatch alone is insufficient. */
        CompletionStage<Boolean> accept(MiningCredit credit);
    }
    public record Batch(int acknowledged, int deferred, List<UUID> failed) {
        public Batch { failed = List.copyOf(failed); }
    }
    private final IntegrationTasks tasks;
    private final Outbox outbox;
    private final Receiver receiver;
    private final int batchSize;
    private final long acceptanceTimeoutMillis;
    private CompletableFuture<Batch> inFlight;
    private boolean closed;

    public MiningNotificationDispatcher(IntegrationTasks tasks, Outbox outbox, Receiver receiver, int batchSize,
                                        Duration acceptanceTimeout) {
        this.tasks = Objects.requireNonNull(tasks);
        this.outbox = Objects.requireNonNull(outbox);
        this.receiver = Objects.requireNonNull(receiver);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        this.batchSize = batchSize;
        acceptanceTimeoutMillis = Objects.requireNonNull(acceptanceTimeout).toMillis();
        if (acceptanceTimeoutMillis < 1) throw new IllegalArgumentException("Acceptance timeout must be at least one millisecond");
    }

    /** Overlapping polls share one batch. Cancelling a caller's view does not cancel other callers.
     * A failed/deferred entry does not prevent processing later entries in the fetched batch.
     * Across batches, poison entries require operator/consumer recovery; no delivery is discarded. */
    public synchronized CompletableFuture<Batch> drain() {
        if (closed) return CompletableFuture.failedFuture(stopped());
        if (inFlight != null) return inFlight.thenApply(result -> result);
        var result = new CompletableFuture<Batch>();
        inFlight = result;
        tasks.database(() -> { ensureOpen(); return List.copyOf(outbox.pendingNotifications(batchSize)); })
                .thenCompose(credits -> {
                    CompletableFuture<Batch> chain = CompletableFuture.completedFuture(new Batch(0, 0, List.of()));
                    for (var credit : credits) chain = chain.thenCompose(batch -> deliver(credit, batch));
                    return chain;
                }).whenComplete((batch, failure) -> {
                    synchronized (this) {
                        if (inFlight == result) inFlight = null;
                    }
                    if (failure == null) result.complete(batch);
                    else result.completeExceptionally(failure);
                });
        return result.thenApply(batch -> batch);
    }

    private CompletableFuture<Batch> deliver(MiningCredit credit, Batch batch) {
        return tasks.server(() -> {
            ensureOpen();
            return Objects.requireNonNull(receiver.accept(credit), "Receiver must return an acceptance stage");
        }).thenCompose(acceptance -> {
            // Timeout only our view. A late consumer commit still requires deduplication on replay.
            var view = new CompletableFuture<Boolean>();
            acceptance.whenComplete((accepted, failure) -> {
                if (failure == null) view.complete(Boolean.TRUE.equals(accepted));
                else view.completeExceptionally(failure);
            });
            return view.orTimeout(acceptanceTimeoutMillis, TimeUnit.MILLISECONDS);
        }).thenCompose(accepted -> {
            ensureOpen();
            if (!Boolean.TRUE.equals(accepted)) return CompletableFuture.completedFuture(false);
            return tasks.database(() -> {
                ensureOpen();
                outbox.notificationDelivered(credit.creditId());
                return true;
            });
        }).handle((acknowledged, failure) -> {
            ensureOpen();
            if (failure != null) {
                var failed = new ArrayList<>(batch.failed()); failed.add(credit.creditId());
                return new Batch(batch.acknowledged(), batch.deferred(), failed);
            }
            return new Batch(batch.acknowledged() + (acknowledged ? 1 : 0),
                    batch.deferred() + (acknowledged ? 0 : 1), batch.failed());
        });
    }

    private synchronized void ensureOpen() { if (closed) throw stopped(); }
    private static IllegalStateException stopped() {
        return new IllegalStateException("Mining notification dispatcher stopped; unacknowledged credits require replay");
    }
    /** Close before its shared IntegrationTasks. A receiver may still commit after close;
     * no queued acknowledgement follows, and replay must deduplicate that consumer commit. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (inFlight != null) inFlight.completeExceptionally(stopped());
        inFlight = null;
    }
}
