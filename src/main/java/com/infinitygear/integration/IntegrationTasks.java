package com.infinitygear.integration;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Tracks DB and server-thread work, including callbacks removed by Bukkit during disable. */
public final class IntegrationTasks implements AutoCloseable {
    private final ExecutorService worker;
    private final Consumer<Runnable> server;
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private boolean closed;

    public IntegrationTasks(ExecutorService worker, Consumer<Runnable> server) {
        this.worker = Objects.requireNonNull(worker); this.server = Objects.requireNonNull(server);
    }
    public <T> CompletableFuture<T> database(Callable<T> work) {
        return submit(() -> {
            if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("MariaDB work is forbidden on the server thread");
            return work.call();
        }, worker::execute);
    }
    public <T> CompletableFuture<T> server(Callable<T> work) { return submit(work, server); }

    private <T> CompletableFuture<T> submit(Callable<T> work, Consumer<Runnable> dispatch) {
        var future = new CompletableFuture<T>();
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("InfinityGear integration stopped; inspect committed receipts, never replay uncertain mining"));
            pending.add(future);
        }
        future.whenComplete((value, failure) -> { synchronized (this) { pending.remove(future); } });
        try {
            dispatch.accept(() -> {
                if (future.isDone()) return;
                try { future.complete(work.call()); }
                catch (Exception failure) { future.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) { future.completeExceptionally(rejected); }
        return future;
    }

    @Override public void close() {
        List<CompletableFuture<?>> outstanding;
        synchronized (this) {
            if (closed) return;
            closed = true; outstanding = List.copyOf(pending);
        }
        outstanding.forEach(future -> future.completeExceptionally(new IllegalStateException("InfinityGear integration stopped; operation outcome may require recovery")));
        worker.shutdownNow();
    }
}
