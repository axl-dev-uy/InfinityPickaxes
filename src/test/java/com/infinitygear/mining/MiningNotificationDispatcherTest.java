package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.integration.IntegrationTasks;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class MiningNotificationDispatcherTest {
    @Test void realExecutorsKeepJournalWorkOffTheServerThread() throws Exception {
        var credit = credit(); var acknowledgements = new AtomicInteger();
        var outbox = new MiningNotificationDispatcher.Outbox() {
            public List<MiningCredit> pendingNotifications(int limit) {
                assertEquals("test-mining-database", Thread.currentThread().getName()); return List.of(credit);
            }
            public void notificationDelivered(UUID id) {
                assertEquals("test-mining-database", Thread.currentThread().getName());
                assertEquals(credit.creditId(), id); acknowledgements.incrementAndGet();
            }
        };
        try (var server = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("test-mining-server").factory());
             var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(
                     Thread.ofPlatform().name("test-mining-database").factory()), server::execute);
             var dispatcher = new MiningNotificationDispatcher(tasks, outbox, c -> {
                 assertEquals("test-mining-server", Thread.currentThread().getName());
                 return CompletableFuture.supplyAsync(() -> true);
             }, 10, Duration.ofSeconds(5))) {
            assertEquals(1, dispatcher.drain().get(5, TimeUnit.SECONDS).acknowledged());
            assertEquals(1, acknowledgements.get());
        }
    }
    static MiningCredit credit() {
        return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.VEIN_MINER, "reset-1", true, true);
    }
    static class Worker extends AbstractExecutorService {
        final Queue<Runnable> queue = new ArrayDeque<>(); boolean shutdown;
        public void execute(Runnable work) { if (shutdown) throw new RejectedExecutionException(); queue.add(work); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; var pending = List.copyOf(queue); queue.clear(); return pending; }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        public boolean awaitTermination(long time, TimeUnit unit) { return isTerminated(); }
    }
    static class Fixture implements AutoCloseable, MiningNotificationDispatcher.Outbox {
        final Worker worker = new Worker(); final Queue<Runnable> server = new ArrayDeque<>();
        final IntegrationTasks tasks = new IntegrationTasks(worker, server::add);
        final List<MiningCredit> pending = new ArrayList<>();
        final List<UUID> acknowledgements = new ArrayList<>();
        String lane = "caller"; boolean failAck, failRead; int reads;
        MiningNotificationDispatcher dispatcher;
        Duration timeout = Duration.ofSeconds(30);
        void receiver(MiningNotificationDispatcher.Receiver receiver) {
            dispatcher = new MiningNotificationDispatcher(tasks, this, credit -> {
                assertEquals("server", lane); return receiver.accept(credit);
            }, 20, timeout);
        }
        public List<MiningCredit> pendingNotifications(int limit) {
            assertEquals("database", lane); reads++;
            if (failRead) throw new IllegalStateException("Database unavailable");
            return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
        }
        public void notificationDelivered(UUID id) {
            assertEquals("database", lane);
            if (failAck) throw new IllegalStateException("Acknowledgement unavailable");
            acknowledgements.add(id); pending.removeIf(c -> c.creditId().equals(id));
        }
        void run(Queue<Runnable> queue, String lane) {
            this.lane = lane;
            while (!queue.isEmpty()) queue.remove().run();
            this.lane = "caller";
        }
        void flush() {
            while (!worker.queue.isEmpty() || !server.isEmpty()) {
                run(worker.queue, "database"); run(server, "server");
            }
        }
        public void close() { if (dispatcher != null) dispatcher.close(); tasks.close(); }
    }

    @Test void acknowledgementWaitsForDurableAcceptanceAndUsesDatabaseLane() {
        try (var f = new Fixture()) {
            var credit = credit(); f.pending.add(credit); var accepted = new CompletableFuture<Boolean>();
            f.receiver(c -> { assertEquals(credit, c); return accepted; });
            var result = f.dispatcher.drain(); f.flush();
            assertFalse(result.isDone()); assertTrue(f.acknowledgements.isEmpty());
            accepted.complete(true);
            assertTrue(f.acknowledgements.isEmpty()); // acceptance completion never performs inline JDBC
            f.flush(); assertEquals(1, result.join().acknowledged());
            assertEquals(List.of(credit.creditId()), f.acknowledgements);
        }
    }
    @Test void deferredAndFailedReceiversDoNotDiscardCreditsOrBlockLaterEntries() {
        try (var f = new Fixture()) {
            var deferred = credit(); var failed = credit(); var accepted = credit();
            f.pending.addAll(List.of(deferred, failed, accepted));
            f.receiver(c -> {
                if (c.equals(failed)) throw new IllegalStateException("Consumer offline");
                return CompletableFuture.completedFuture(c.equals(accepted));
            });
            var result = f.dispatcher.drain(); f.flush();
            assertEquals(new MiningNotificationDispatcher.Batch(1, 1, List.of(failed.creditId())), result.join());
            assertEquals(List.of(deferred, failed), f.pending);
        }
    }
    @Test void failedAcknowledgementReplaysSameCreditToAnIdempotentReceiver() {
        try (var f = new Fixture()) {
            var credit = credit(); f.pending.add(credit); f.failAck = true;
            var received = new HashSet<UUID>(); var grants = new AtomicInteger();
            f.receiver(c -> {
                if (received.add(c.creditId())) grants.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            });
            var first = f.dispatcher.drain(); f.flush();
            assertEquals(List.of(credit.creditId()), first.join().failed());
            f.failAck = false;
            var replay = f.dispatcher.drain(); f.flush();
            assertEquals(1, replay.join().acknowledged()); assertEquals(1, grants.get());
            assertTrue(f.pending.isEmpty());
        }
    }
    @Test void overlappingPollsShareWorkAndCallerCancellationIsIsolated() {
        try (var f = new Fixture()) {
            f.pending.add(credit()); var received = new AtomicInteger();
            f.receiver(c -> { received.incrementAndGet(); return CompletableFuture.completedFuture(true); });
            var first = f.dispatcher.drain(); var second = f.dispatcher.drain(); first.cancel(false);
            f.flush(); assertEquals(1, second.join().acknowledged());
            assertEquals(1, f.reads); assertEquals(1, received.get());
        }
    }
    @Test void closeSkipsQueuedServerDeliveryAndFailsPendingPolls() {
        try (var f = new Fixture()) {
            f.pending.add(credit()); f.receiver(c -> { fail("Queued receiver must not run"); return null; });
            var result = f.dispatcher.drain(); f.run(f.worker.queue, "database");
            f.dispatcher.close(); f.flush();
            assertTrue(result.isCompletedExceptionally()); assertTrue(f.acknowledgements.isEmpty());
            assertTrue(f.dispatcher.drain().isCompletedExceptionally());
        }
    }
    @Test void closeWhileReceiverIsPendingDoesNotHangOrAcknowledgeLateAcceptance() {
        try (var f = new Fixture()) {
            f.pending.add(credit()); var accepted = new CompletableFuture<Boolean>(); f.receiver(c -> accepted);
            var result = f.dispatcher.drain(); f.flush(); f.dispatcher.close();
            assertTrue(result.isCompletedExceptionally());
            accepted.complete(true); f.flush(); assertTrue(f.acknowledgements.isEmpty());
        }
    }
    @Test void readFailureIsVisibleAndNextPollCanRetry() {
        try (var f = new Fixture()) {
            f.receiver(c -> CompletableFuture.completedFuture(true)); f.failRead = true;
            var first = f.dispatcher.drain(); f.flush(); assertTrue(first.isCompletedExceptionally());
            f.failRead = false; var retry = f.dispatcher.drain(); f.flush();
            assertEquals(new MiningNotificationDispatcher.Batch(0, 0, List.of()), retry.join());
        }
    }
    @Test void boundsPreventUnboundedBatchRequests() {
        try (var f = new Fixture()) {
            for (int size : List.of(0, -1, 1001)) assertThrows(IllegalArgumentException.class,
                    () -> new MiningNotificationDispatcher(f.tasks, f, c -> CompletableFuture.completedFuture(true), size, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> new MiningNotificationDispatcher(f.tasks, f,
                    c -> CompletableFuture.completedFuture(true), 20, Duration.ZERO));
        }
    }
    @Test void unansweredAcceptanceTimesOutWithoutCancellingReceiverOrAcknowledgingLateCommit() throws Exception {
        try (var f = new Fixture()) {
            var credit = credit(); f.pending.add(credit);
            f.timeout = Duration.ofMillis(10);
            var accepted = new CompletableFuture<Boolean>(); f.receiver(c -> accepted);
            var result = f.dispatcher.drain(); f.flush();
            assertEquals(List.of(credit.creditId()), result.get(5, TimeUnit.SECONDS).failed());
            assertFalse(accepted.isDone());
            accepted.complete(true); f.flush();
            assertTrue(f.acknowledgements.isEmpty()); assertEquals(List.of(credit), f.pending);
            var retry = f.dispatcher.drain(); f.flush(); assertEquals(1, retry.join().acknowledged());
        }
    }
}
