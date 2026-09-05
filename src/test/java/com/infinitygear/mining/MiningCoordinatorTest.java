package com.infinitygear.mining;

import com.infinitygear.api.v1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MiningCoordinatorTest {
    static class Journal implements MiningCoordinator.Journal {
        final Map<UUID, String> states = new HashMap<>();
        public boolean reserve(MiningCredit c) { return states.putIfAbsent(c.instanceId(), "RESERVED") == null; }
        public void complete(UUID id) { states.put(id, "COMPLETED"); }
        public void needsRecovery(UUID id) { states.put(id, "RECOVERY"); }
    }
    static MiningCredit credit(UUID instance, MiningCredit.Source source, boolean placed, boolean success, String block, String generation) {
        return new MiningCredit(UUID.randomUUID(), instance, UUID.randomUUID(), UUID.randomUUID(), "infinitygear:pickaxe",
                UUID.randomUUID(), 1, 2, 3, block, source, generation, !placed, success);
    }
    @ParameterizedTest @EnumSource(MiningCredit.Source.class)
    void normalAndAoeInstancesCreditOnceDespiteNestedOverlappingReplays(MiningCredit.Source source) {
        var journal = new Journal(); var service = new MiningCoordinator(journal);
        var xp = new AtomicInteger(); var events = new AtomicInteger();
        var original = credit(UUID.randomUUID(), source, false, true, "minecraft:stone", "reset-1");
        assertTrue(service.credit(original, xp::incrementAndGet, c -> events.incrementAndGet()));
        assertFalse(service.credit(original, xp::incrementAndGet, c -> events.incrementAndGet()));
        // A second event/credit UUID must not bypass physical-instance deduplication.
        assertFalse(service.credit(credit(original.instanceId(), source, false, true, "minecraft:stone", "reset-1"), xp::incrementAndGet, c -> events.incrementAndGet()));
        var neighbor = credit(UUID.randomUUID(), source, false, true, "minecraft:stone", "reset-1");
        assertTrue(service.credit(neighbor, xp::incrementAndGet, c -> events.incrementAndGet()));
        assertEquals(2, xp.get()); assertEquals(2, events.get());
        assertFalse(new MiningCoordinator(journal).credit(original, xp::incrementAndGet, c -> fail("Restart replay")));
    }
    @Test void deniedCancelledFailedPlacedAndAirNeverReserveOrAward() {
        var journal = new Journal(); var service = new MiningCoordinator(journal);
        for (var block : List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air"))
            assertFalse(service.credit(credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, false, true, block, "1"), () -> fail(), c -> fail()));
        assertFalse(service.credit(credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, true, true, "minecraft:stone", "1"), () -> fail(), c -> fail()));
        assertFalse(service.credit(credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, false, false, "minecraft:stone", "1"), () -> fail(), c -> fail()));
        assertTrue(journal.states.isEmpty());
    }
    @Test void sameCoordinatesCanCreditMultipleAuthoritativeGenerationsWithoutClockHeuristics() {
        var service = new MiningCoordinator(new Journal()); var count = new AtomicInteger();
        var seed = credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, false, true, "minecraft:stone", "reset-1");
        for (String generation : List.of("reset-1", "reset-2"))
            assertTrue(service.credit(new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), seed.playerId(), seed.pickaxeId(), seed.profileId(),
                    seed.worldId(), seed.x(), seed.y(), seed.z(), seed.originalBlockData(), seed.source(), generation, true, true), count::incrementAndGet, c -> {}));
        assertEquals(2, count.get());
    }
    @Test void ambiguousXpFailureRequiresRecoveryWithoutAutomaticRetry() {
        var journal = new Journal(); var service = new MiningCoordinator(journal);
        var credit = credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, false, true, "minecraft:stone", "1");
        assertThrows(IllegalStateException.class, () -> service.credit(credit, () -> { throw new IllegalStateException(); }, c -> fail()));
        assertEquals("RECOVERY", journal.states.get(credit.instanceId()));
        assertFalse(service.credit(credit, () -> fail(), c -> fail()));
    }
    @Test void notificationCannotUndoXpOrBecomeCancellable() {
        assertFalse(org.bukkit.event.Cancellable.class.isAssignableFrom(CreditedBlockEvent.class));
        var service = new MiningCoordinator(new Journal()); var count = new AtomicInteger();
        var credit = credit(UUID.randomUUID(), MiningCredit.Source.NORMAL, false, true, "minecraft:stone", "1");
        assertThrows(IllegalStateException.class, () -> service.credit(credit, count::incrementAndGet, c -> { throw new IllegalStateException(); }));
        assertFalse(service.credit(credit, count::incrementAndGet, c -> {})); assertEquals(1, count.get());
    }
    @Test void beginRetainsOriginalStateAndCompletionIsIdempotent() {
        var service = new MiningCoordinator(new Journal());
        var original = credit(UUID.randomUUID(), MiningCredit.Source.VEIN_MINER, false, false, "minecraft:diamond_ore", "reset-1");
        service.begin(original); service.begin(original);
        var count = new AtomicInteger();
        assertTrue(service.complete(original.instanceId(), true, count::incrementAndGet,
                result -> assertEquals("minecraft:diamond_ore", result.originalBlockData())));
        assertFalse(service.complete(original.instanceId(), true, count::incrementAndGet, c -> fail()));
        assertEquals(1, count.get());
    }
}
