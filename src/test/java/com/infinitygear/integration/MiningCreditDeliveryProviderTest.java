package com.infinitygear.integration;

import com.infinitygear.api.v1.MiningCredit;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MiningCreditDeliveryProviderTest {
    @Test void activationReturnsBeforeDatabaseCommit() throws Exception {
        var caller = Thread.currentThread();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new MiningCreditDeliveryProvider.Store() {
            public void activate() {
                assertNotSame(caller, Thread.currentThread());
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            }
            public List<MiningCredit> pendingNotifications(int limit) { return List.of(); }
            public void notificationDelivered(UUID id) { fail("No credit enrolled"); }
        };
        try (var worker = Executors.newSingleThreadExecutor();
             var tasks = new IntegrationTasks(worker, Runnable::run)) {
            var service = new MiningCreditDeliveryProvider(tasks, store, Duration.ofSeconds(1));
            var activation = service.activate().toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(activation.isDone());
            release.countDown();
            activation.get(5, TimeUnit.SECONDS);
            service.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void absentConsumerCannotAcknowledgeAndUnregisterRetainsPendingDelivery() throws Exception {
        var credit = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "generation-1", true, true);
        var acknowledgements = new AtomicInteger();
        var activations = new AtomicInteger();
        var store = new MiningCreditDeliveryProvider.Store() {
            public void activate() { activations.incrementAndGet(); }
            public List<MiningCredit> pendingNotifications(int limit) {
                return acknowledgements.get() == 0 ? List.of(credit) : List.of();
            }
            public void notificationDelivered(UUID id) {
                assertEquals(credit.creditId(), id);
                acknowledgements.incrementAndGet();
            }
        };
        try (var worker = Executors.newSingleThreadExecutor();
             var server = Executors.newSingleThreadExecutor();
             var tasks = new IntegrationTasks(worker, server::execute)) {
            var service = new MiningCreditDeliveryProvider(tasks, store, Duration.ofSeconds(5));
            try {
            assertEquals(0, service.drain().get(5, TimeUnit.SECONDS).acknowledged());
            assertEquals(0, acknowledgements.get());
            service.activate().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, activations.get());
            var decision = new CompletableFuture<Boolean>();
            var callback = new CountDownLatch(1);
            var registration = service.register(c -> { callback.countDown(); return decision; });
            assertThrows(IllegalStateException.class,
                    () -> service.register(c -> CompletableFuture.completedFuture(true)));
            var poll = service.drain();
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            var fence = registration.unregister();
            decision.complete(true);
            fence.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> poll.get(5, TimeUnit.SECONDS));
            assertEquals(0, acknowledgements.get());
            var replay = service.register(c -> CompletableFuture.completedFuture(true));
            assertEquals(1, service.drain().get(5, TimeUnit.SECONDS).acknowledged());
            replay.unregister().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, acknowledgements.get());
            } finally {
                service.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void unregisterFenceWaitsForAnAcknowledgementAlreadyExecuting() throws Exception {
        var credit = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "generation-2", true, true);
        var ackEntered = new CountDownLatch(1);
        var releaseAck = new CountDownLatch(1);
        var acknowledgements = new AtomicInteger();
        var store = new MiningCreditDeliveryProvider.Store() {
            public void activate() { }
            public List<MiningCredit> pendingNotifications(int limit) { return List.of(credit); }
            public void notificationDelivered(UUID id) {
                ackEntered.countDown();
                try { assertTrue(releaseAck.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                acknowledgements.incrementAndGet();
            }
        };
        try (var worker = Executors.newSingleThreadExecutor();
             var server = Executors.newSingleThreadExecutor();
             var tasks = new IntegrationTasks(worker, server::execute)) {
            var service = new MiningCreditDeliveryProvider(tasks, store, Duration.ofSeconds(5));
            var registration = service.register(c -> CompletableFuture.completedFuture(true));
            var poll = service.drain();
            assertTrue(ackEntered.await(5, TimeUnit.SECONDS));
            var fence = registration.unregister().toCompletableFuture();
            assertFalse(fence.isDone());
            releaseAck.countDown();
            fence.get(5, TimeUnit.SECONDS);
            assertEquals(1, acknowledgements.get());
            assertTrue(poll.isDone());
            service.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally { releaseAck.countDown(); }
    }

    @Test void deferredHeadNeverInvokesLaterPityDecision() throws Exception {
        var first = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "generation-3", true, true);
        var second = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 2, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "generation-4", true, true);
        var pending = new ArrayList<>(List.of(first, second));
        var visited = new ArrayList<UUID>();
        var store = new MiningCreditDeliveryProvider.Store() {
            public void activate() { }
            public List<MiningCredit> pendingNotifications(int limit) {
                assertEquals(1, limit);
                return pending.isEmpty() ? List.of() : List.of(pending.getFirst());
            }
            public void notificationDelivered(UUID id) { pending.removeIf(c -> c.creditId().equals(id)); }
        };
        try (var worker = Executors.newSingleThreadExecutor();
             var server = Executors.newSingleThreadExecutor();
             var tasks = new IntegrationTasks(worker, server::execute)) {
            var service = new MiningCreditDeliveryProvider(tasks, store, Duration.ofSeconds(5));
            var registration = service.register(c -> {
                visited.add(c.creditId());
                return CompletableFuture.completedFuture(!c.equals(first));
            });
            assertEquals(1, service.drain().get(5, TimeUnit.SECONDS).deferred());
            assertEquals(1, service.drain().get(5, TimeUnit.SECONDS).deferred());
            assertEquals(List.of(first.creditId(), first.creditId()), visited);
            assertEquals(List.of(first, second), pending);
            registration.unregister().toCompletableFuture().get(5, TimeUnit.SECONDS);
            service.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
