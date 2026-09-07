package com.infinitygear.integration;

import com.infinitygear.api.v1.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveContractsTest {
    @Test void exactValueRejectsTruncationAndOrdinaryValue() {
        assertEquals(new BigDecimal("0.100000000000000000"), BookLedger.exactValue(new BigDecimal("0.1")));
        assertThrows(ArithmeticException.class, () -> BookLedger.exactValue(new BigDecimal("0.0000000000000000001")));
        assertThrows(IllegalArgumentException.class, () -> new ProvenancePolicy.Source(UUID.randomUUID(), false, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> BookLedger.exactValue(BigDecimal.valueOf(-1)));
    }
    @Test void everyUnresolvedLifecycleRuleRejectsWithoutInventingValue() {
        var unequal = List.of(new ProvenancePolicy.Source(UUID.randomUUID(), true, BigDecimal.ONE),
                new ProvenancePolicy.Source(UUID.randomUUID(), true, BigDecimal.TEN));
        var mixed = List.of(unequal.getFirst(), new ProvenancePolicy.Source(UUID.randomUUID(), false, BigDecimal.ZERO));
        for (var operation : ProvenancePolicy.Operation.values()) for (var sources : List.of(unequal, mixed)) {
            var decision = ProvenancePolicy.unresolved().decide(operation, sources, 1);
            assertFalse(decision.allowed()); assertTrue(decision.outputValues().isEmpty());
        }
    }
    @Test void dtoCollectionsAndIssuanceBytesAreDefensiveCopies() {
        var targets = new HashSet<>(Set.of("pickaxe"));
        var profile = new ArchiveIntegrationService.Profile("id", true, "label", targets, List.of());
        targets.clear(); assertEquals(Set.of("pickaxe"), profile.targets());
        assertThrows(UnsupportedOperationException.class, () -> profile.targets().clear());
        byte[] bytes = {1}; var issued = new BookIssuanceService.IssuedBook(null, bytes);
        bytes[0] = 2; issued.serializedItem()[0] = 3; assertEquals(1, issued.serializedItem()[0]);
    }
    @Test void dtoSignaturesDoNotExposeImplementationPackages() {
        for (Class<?> type : List.of(ArchiveIntegrationService.class, BookLedger.class, BookIssuanceService.class,
                MiningAuthority.class, MiningCompletion.class, MiningCredit.class,
                ProvenancePolicy.class, ProvenanceTransition.class)) check(type);
    }
    @Test void miningCompletionRequiresEveryConfirmedPhysicalFact() {
        var completion = new MiningCompletion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "acceptance", UUID.randomUUID(),
                1, 2, 3, "minecraft:stone", MiningCredit.Source.NORMAL, "NATURAL",
                true, true, true, true, true, true, false, Instant.now(), Map.of("producer", "test"), "fence-1");
        assertTrue(completion.confirmed());
        assertFalse(new MiningCompletion(completion.completionId(), completion.instanceId(), completion.operationId(),
                completion.playerId(), completion.itemId(), completion.worldId(), completion.mine(), completion.generationId(),
                completion.x(), completion.y(), completion.z(), completion.originalBlockData(), completion.source(), completion.placement(),
                true, true, true, true, true, false, false, completion.observedAt(), completion.versions(), completion.configurationRevision()).confirmed());
    }
    private void check(Class<?> type) {
        for (var method : type.getDeclaredMethods()) {
            String signature = method.toGenericString();
            assertFalse(signature.contains("com.infinitypickaxes."), signature);
            for (String internal : List.of("gear", "enchant", "data", "persistence", "integration"))
                assertFalse(signature.contains("com.infinitygear." + internal + "."), signature);
        }
        for (Class<?> nested : type.getDeclaredClasses()) check(nested);
    }
}
