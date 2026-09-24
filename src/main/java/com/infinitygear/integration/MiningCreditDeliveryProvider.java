package com.infinitygear.integration;

import com.infinitygear.api.v1.MiningCreditDeliveryService;
import com.infinitygear.mining.MiningNotificationDispatcher;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One live Archive callback over an independently acknowledged, durable receipt stream. */
public final class MiningCreditDeliveryProvider implements MiningCreditDeliveryService {
    public interface Store extends MiningNotificationDispatcher.Outbox {
        void activate();
    }

    private final IntegrationTasks tasks;
    private final Store store;
    private final Duration timeout;
    private RegistrationImpl registration;
    private boolean closed;
    private CompletionStage<Void> shutdownFence;

    public MiningCreditDeliveryProvider(IntegrationTasks tasks, Store store, Duration timeout) {
        this.tasks = Objects.requireNonNull(tasks);
        this.store = Objects.requireNonNull(store);
        this.timeout = Objects.requireNonNull(timeout);
        // One credit per poll enforces head-of-line decisions for Archive pity.
        try (var validation = new MiningNotificationDispatcher(tasks, store,
                credit -> CompletableFuture.completedFuture(false), 1, timeout)) { }
    }

    @Override public synchronized Registration register(Consumer consumer) {
        Objects.requireNonNull(consumer);
        if (org.bukkit.Bukkit.getServer() != null && !org.bukkit.Bukkit.isPrimaryThread())
            throw new IllegalStateException("Archive consumer registration requires the server thread");
        if (closed) throw new IllegalStateException("Mining credit delivery stopped");
        if (registration != null) throw new IllegalStateException("Archive consumer already registered");
        var dispatcher = new MiningNotificationDispatcher(tasks, store, consumer::accept, 1, timeout);
        registration = new RegistrationImpl(dispatcher);
        return registration;
    }

    @Override public synchronized CompletionStage<Void> activate() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Mining credit delivery stopped"));
        return tasks.database(() -> { store.activate(); return null; });
    }

    /** Polling is skipped while Archive is absent. No callback or acknowledgement can occur. */
    public synchronized CompletableFuture<MiningNotificationDispatcher.Batch> drain() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Mining credit delivery stopped"));
        if (registration == null) return CompletableFuture.completedFuture(new MiningNotificationDispatcher.Batch(0, 0, java.util.List.of()));
        return registration.dispatcher.drain();
    }

    /** Complete this fence before closing the shared IntegrationTasks worker. */
    public synchronized CompletionStage<Void> shutdown() {
        if (closed) return shutdownFence;
        closed = true;
        shutdownFence = registration == null ? tasks.database(() -> null) : registration.unregister();
        return shutdownFence;
    }

    private final class RegistrationImpl implements Registration {
        private final MiningNotificationDispatcher dispatcher;
        private boolean stopped;
        private CompletionStage<Void> fence;
        private RegistrationImpl(MiningNotificationDispatcher dispatcher) { this.dispatcher = dispatcher; }
        @Override public CompletionStage<Void> unregister() {
            synchronized (MiningCreditDeliveryProvider.this) {
                if (stopped) return fence;
                stopped = true;
                dispatcher.close();
                if (registration == this) registration = null;
                // The integration worker is serial. A queued acknowledgement observes the
                // closed dispatcher; an acknowledgement already executing finishes before this fence.
                fence = tasks.database(() -> null);
                return fence;
            }
        }
    }
}
