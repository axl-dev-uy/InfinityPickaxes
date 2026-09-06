package com.infinitygear.integration;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationTasksTest {
    @Test void cancelledBukkitCallbacksCompleteExceptionallyOnClose() {
        var callbacks = new ArrayList<Runnable>(); var count = new AtomicInteger();
        var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add);
        var future = tasks.server(count::incrementAndGet);
        tasks.close(); tasks.close();
        assertTrue(future.isCompletedExceptionally());
        callbacks.forEach(Runnable::run); assertEquals(0, count.get());
        assertTrue(tasks.server(count::incrementAndGet).isCompletedExceptionally());
        assertTrue(tasks.database(count::incrementAndGet).isCompletedExceptionally());
    }
    @Test void queuedDatabaseWorkIsNotLeftPendingAfterShutdown() throws Exception {
        var running = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var first = tasks.database(() -> { running.countDown(); release.await(); return 1; });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var second = tasks.database(() -> 2);
            tasks.close(); release.countDown();
            assertTrue(first.isCompletedExceptionally()); assertTrue(second.isCompletedExceptionally());
        } finally { release.countDown(); }
    }
    @Test void schedulerRejectionPropagatesInsteadOfHanging() {
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callback -> { throw new RejectedExecutionException(); })) {
            assertThrows(CompletionException.class, () -> tasks.server(() -> 1).join());
        }
    }
}
