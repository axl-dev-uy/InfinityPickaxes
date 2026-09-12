package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLifecycleRequest;
import com.infinitygear.api.v1.BookLifecycleTransaction;
import com.infinitygear.api.v1.ProvenancePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.infinitygear.api.v1.BookLifecycleRequest.InputKind.ARCHIVE_BOOK;
import static com.infinitygear.api.v1.BookLifecycleRequest.InputKind.ORDINARY_BOOK;
import static com.infinitygear.api.v1.BookLifecycleRequest.LineageKind.ATTACHMENT;
import static com.infinitygear.api.v1.BookLifecycleRequest.LineageKind.BOOK;
import static org.junit.jupiter.api.Assertions.*;

class BookLifecycleContractsTest {
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID FIRST_BOOK = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID SECOND_BOOK = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID OUTPUT_BOOK = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String FORTUNE = "minecraft:fortune";

    @Test void canonicalFingerprintIncludesPhysicalAndLineagePayloadAndNormalizesExactValues() {
        var request = application(OPERATION, ACTOR, 7, 5, new byte[]{1}, new byte[]{2}, new BigDecimal("0.1"));
        var equivalent = application(OPERATION, ACTOR, 7, 5, new byte[]{1}, new byte[]{2},
                new BigDecimal("0.100000000000000000"));
        assertEquals(64, request.fingerprint().length());
        assertEquals(request.fingerprint(), equivalent.fingerprint());
        assertNotEquals(request.fingerprint(), application(OPERATION, ACTOR, 8, 5,
                new byte[]{1}, new byte[]{2}, new BigDecimal("0.1")).fingerprint());
        assertNotEquals(request.fingerprint(), application(OPERATION, ACTOR, 7, 6,
                new byte[]{1}, new byte[]{2}, new BigDecimal("0.1")).fingerprint());
        assertNotEquals(request.fingerprint(), application(OPERATION, ACTOR, 7, 5,
                new byte[]{9}, new byte[]{2}, new BigDecimal("0.1")).fingerprint());
        assertNotEquals(request.fingerprint(), application(OPERATION, ACTOR, 7, 5,
                new byte[]{1}, new byte[]{3}, new BigDecimal("0.1")).fingerprint());
        assertNotEquals(request.fingerprint(), application(OPERATION, UUID.randomUUID(), 7, 5,
                new byte[]{1}, new byte[]{2}, new BigDecimal("0.1")).fingerprint());
    }

    @Test void canonicalFingerprintDoesNotDependOnSetLikeInputOrLineageOrdering() {
        var first = fusion(List.of(FIRST_BOOK, SECOND_BOOK), List.of(
                value(BOOK, FIRST_BOOK, "0.1"), value(BOOK, SECOND_BOOK, "0.2")));
        var second = fusion(List.of(SECOND_BOOK, FIRST_BOOK), List.of(
                value(BOOK, SECOND_BOOK, "0.2"), value(BOOK, FIRST_BOOK, "0.1")));
        assertEquals(first, second);
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test void replayAcceptsOnlyTheIdenticalOperationPayload() {
        var request = application(OPERATION, ACTOR, 7, 5, new byte[]{1}, new byte[]{2}, BigDecimal.ONE);
        var state = new BookLifecycleTransaction.State(request, request.fingerprint(),
                BookLifecycleTransaction.Phase.PREPARED, Instant.EPOCH, Instant.EPOCH);
        assertSame(state, state.requireReplay(application(OPERATION, ACTOR, 7, 5,
                new byte[]{1}, new byte[]{2}, BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> state.requireReplay(
                application(OPERATION, ACTOR, 7, 6, new byte[]{1}, new byte[]{2}, BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> state.requireReplay(
                application(UUID.randomUUID(), ACTOR, 7, 5, new byte[]{1}, new byte[]{2}, BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleTransaction.State(
                request, "0".repeat(64), BookLifecycleTransaction.Phase.PREPARED, Instant.EPOCH, Instant.EPOCH));
    }

    @Test void phasesModelEachRecoveryBoundaryAndRejectRegression() {
        usingPermitted(BookLifecycleTransaction.Phase.PREPARED,
                BookLifecycleTransaction.Phase.CUSTODY_MARKED);
        usingPermitted(BookLifecycleTransaction.Phase.CUSTODY_MARKED,
                BookLifecycleTransaction.Phase.SOURCES_REMOVED);
        usingPermitted(BookLifecycleTransaction.Phase.SOURCES_REMOVED,
                BookLifecycleTransaction.Phase.EQUIPMENT_MUTATED);
        usingPermitted(BookLifecycleTransaction.Phase.EQUIPMENT_MUTATED,
                BookLifecycleTransaction.Phase.OUTPUTS_INSERTED);
        usingPermitted(BookLifecycleTransaction.Phase.OUTPUTS_INSERTED,
                BookLifecycleTransaction.Phase.FINALIZED);
        usingPermitted(BookLifecycleTransaction.Phase.FINALIZED,
                BookLifecycleTransaction.Phase.ACKNOWLEDGED);
        assertTrue(BookLifecycleTransaction.Phase.PREPARED.permits(BookLifecycleTransaction.Phase.PREPARED));
        assertTrue(BookLifecycleTransaction.Phase.PREPARED.permits(BookLifecycleTransaction.Phase.ABORTED));
        assertTrue(BookLifecycleTransaction.Phase.SOURCES_REMOVED.permits(BookLifecycleTransaction.Phase.ROLLED_BACK));
        assertFalse(BookLifecycleTransaction.Phase.SOURCES_REMOVED.permits(BookLifecycleTransaction.Phase.ABORTED));
        assertFalse(BookLifecycleTransaction.Phase.FINALIZED.permits(BookLifecycleTransaction.Phase.ABORTED));
        assertFalse(BookLifecycleTransaction.Phase.ACKNOWLEDGED.permits(BookLifecycleTransaction.Phase.FINALIZED));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleTransaction.Advance(
                OPERATION, "a".repeat(64), BookLifecycleTransaction.Phase.EQUIPMENT_MUTATED,
                BookLifecycleTransaction.Phase.SOURCES_REMOVED));
    }

    @Test void requestRejectsInvalidOperationShapesAndValueInflation() {
        var valid = application(OPERATION, ACTOR, 7, 5, new byte[]{1}, new byte[]{2}, BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest(
                2, valid.operationId(), valid.actorId(), valid.operation(), valid.equipment(), valid.inputs(),
                valid.enchantmentChange(), valid.outputs(), valid.lineageDecision()));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest(
                1, valid.operationId(), valid.actorId(), ProvenancePolicy.Operation.PAIR_FUSION,
                valid.equipment(), valid.inputs(), valid.enchantmentChange(), valid.outputs(), valid.lineageDecision()));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest.Input(
                ARCHIVE_BOOK, Optional.of(FIRST_BOOK), slot(5), 2, 1, image(1)));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest.Input(
                ORDINARY_BOOK, Optional.of(FIRST_BOOK), slot(5), 1, 1, image(1)));
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest.LineageDecision(
                "test:policy", "inflation", List.of(value(BOOK, FIRST_BOOK, "0.1")),
                List.of(value(ATTACHMENT, EQUIPMENT, "0.2")), BigDecimal.ZERO));
    }

    @Test void everyOperationHasARepresentableMechanicsPayloadWithoutAuthorizingPolicy() {
        assertDoesNotThrow(() -> application(OPERATION, ACTOR, 0, 5,
                new byte[]{1}, new byte[]{2}, BigDecimal.ONE));

        var tracked = new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(FIRST_BOOK),
                slot(5), 1, 1, image(5));
        var replacementOutput = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 1, slot(5), image(6));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, OPERATION, ACTOR,
                ProvenancePolicy.Operation.REPLACE, Optional.of(equipment(3, 1)), List.of(tracked),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 1, 2, true, true)),
                List.of(replacementOutput), decision(
                        List.of(value(ATTACHMENT, EQUIPMENT, "1"), value(BOOK, FIRST_BOOK, "2")),
                        List.of(value(ATTACHMENT, EQUIPMENT, "2"), value(BOOK, OUTPUT_BOOK, "1")))));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, OPERATION, ACTOR,
                ProvenancePolicy.Operation.REPLACE, Optional.of(equipment(3, 1)), List.of(tracked),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 1, 2, false, true)),
                List.of(), decision(List.of(value(BOOK, FIRST_BOOK, "2")),
                        List.of(value(ATTACHMENT, EQUIPMENT, "2")))));

        var removedOutput = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 2, slot(5), image(6));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, OPERATION, ACTOR,
                ProvenancePolicy.Operation.REMOVE, Optional.of(equipment(3, 1)), List.of(),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 2, 0, true, false)),
                List.of(removedOutput), decision(List.of(value(ATTACHMENT, EQUIPMENT, "1")),
                        List.of(value(BOOK, OUTPUT_BOOK, "1")))));

        var blank = new BookLifecycleRequest.Input(ORDINARY_BOOK, Optional.empty(), slot(5), 8, 1, image(5));
        var transferredOutput = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 2, slot(6), image(6));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, OPERATION, ACTOR,
                ProvenancePolicy.Operation.TRANSFER, Optional.of(equipment(3, 1)), List.of(blank),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 2, 0, true, false)),
                List.of(transferredOutput), decision(List.of(value(ATTACHMENT, EQUIPMENT, "1")),
                        List.of(value(BOOK, OUTPUT_BOOK, "1")))));

        var pair = fusion(List.of(FIRST_BOOK, SECOND_BOOK),
                List.of(value(BOOK, FIRST_BOOK, "0.1"), value(BOOK, SECOND_BOOK, "0.2")));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, pair.operationId(), pair.actorId(),
                ProvenancePolicy.Operation.BULK_FUSION, Optional.empty(), pair.inputs(), Optional.empty(),
                pair.outputs(), pair.lineageDecision()));
    }

    @Test void ordinaryPhysicalInputCannotAcquireArchiveValue() {
        var equipment = equipment(4, 3);
        var blank = new BookLifecycleRequest.Input(ORDINARY_BOOK, Optional.empty(), slot(5), 1, 1, image(4));
        var change = new BookLifecycleRequest.EnchantmentChange(FORTUNE, 2, 0, true, false);
        var output = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 2, slot(5), image(5));
        var attached = value(ATTACHMENT, EQUIPMENT, "1");
        var trackedOutput = value(BOOK, OUTPUT_BOOK, "1");
        var valid = new BookLifecycleRequest(1, OPERATION, ACTOR, ProvenancePolicy.Operation.TRANSFER,
                Optional.of(equipment), List.of(blank), Optional.of(change), List.of(output),
                decision(List.of(attached), List.of(trackedOutput)));
        assertEquals(new BigDecimal("1.000000000000000000"),
                valid.lineageDecision().destinations().getFirst().value());

        var ordinaryAsValue = value(BOOK, FIRST_BOOK, "1");
        assertThrows(IllegalArgumentException.class, () -> new BookLifecycleRequest(
                1, OPERATION, ACTOR, ProvenancePolicy.Operation.TRANSFER, Optional.of(equipment), List.of(blank),
                Optional.of(change), List.of(output), decision(List.of(attached, ordinaryAsValue), List.of(trackedOutput))));

        var tracked = new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(FIRST_BOOK), slot(1), 1, 1, image(1));
        var ordinary = new BookLifecycleRequest.Input(ORDINARY_BOOK, Optional.empty(), slot(2), 1, 1, image(2));
        var mixedOutput = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 2, slot(1), image(3));
        assertDoesNotThrow(() -> new BookLifecycleRequest(1, OPERATION, ACTOR,
                ProvenancePolicy.Operation.PAIR_FUSION, Optional.empty(), List.of(tracked, ordinary),
                Optional.empty(), List.of(mixedOutput), decision(List.of(value(BOOK, FIRST_BOOK, "1")),
                        List.of(value(BOOK, OUTPUT_BOOK, "1")))));
    }

    @Test void dtoCollectionsAndSerializedImagesAreDefensive() {
        byte[] bytes = {1, 2};
        var image = new BookLifecycleRequest.ItemImage(bytes);
        bytes[0] = 9;
        image.serializedItem()[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, image.serializedItem());
        assertEquals(new BookLifecycleRequest.ItemImage(new byte[]{1, 2}), image);

        var inputs = new ArrayList<BookLifecycleRequest.Input>();
        inputs.add(new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(FIRST_BOOK), slot(5), 1, 1, image));
        var request = new BookLifecycleRequest(1, OPERATION, ACTOR, ProvenancePolicy.Operation.APPLY,
                Optional.of(equipment(0, 3)), inputs,
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 0, 1, false, true)), List.of(),
                decision(List.of(value(BOOK, FIRST_BOOK, "1")), List.of(value(ATTACHMENT, EQUIPMENT, "1"))));
        inputs.clear();
        assertEquals(1, request.inputs().size());
        assertThrows(UnsupportedOperationException.class, () -> request.inputs().clear());
    }

    private static BookLifecycleRequest application(UUID operation, UUID actor, long revision, int sourceSlot,
                                                    byte[] before, byte[] after, BigDecimal value) {
        var equipmentSlot = new BookLifecycleRequest.InventorySlot(actor, "player-storage", 4);
        var inputSlot = new BookLifecycleRequest.InventorySlot(actor, "player-storage", sourceSlot);
        var equipment = new BookLifecycleRequest.Equipment(EQUIPMENT, revision, revision + 1, equipmentSlot,
                new BookLifecycleRequest.ItemImage(before), new BookLifecycleRequest.ItemImage(after));
        var input = new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(FIRST_BOOK), inputSlot, 1, 1,
                new BookLifecycleRequest.ItemImage(new byte[]{7}));
        var lineage = new BookLifecycleRequest.LineageDecision("test:no-replacement", "test mechanics only",
                List.of(new BookLifecycleRequest.ValueEntry(
                        new BookLifecycleRequest.LineageNode(BOOK, FIRST_BOOK, FORTUNE), value)),
                List.of(new BookLifecycleRequest.ValueEntry(
                        new BookLifecycleRequest.LineageNode(ATTACHMENT, EQUIPMENT, FORTUNE), value)),
                BigDecimal.ZERO);
        return new BookLifecycleRequest(1, operation, actor, ProvenancePolicy.Operation.APPLY,
                Optional.of(equipment), List.of(input),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(FORTUNE, 0, 1, false, true)), List.of(), lineage);
    }

    private static BookLifecycleRequest fusion(List<UUID> ids, List<BookLifecycleRequest.ValueEntry> sources) {
        var inputs = ids.stream().map(id -> new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(id),
                slot(id.equals(FIRST_BOOK) ? 1 : 2), 1, 1,
                image(id.equals(FIRST_BOOK) ? 1 : 2))).toList();
        var output = new BookLifecycleRequest.Output(OUTPUT_BOOK, FORTUNE, 2, slot(1), image(3));
        return new BookLifecycleRequest(1, OPERATION, ACTOR, ProvenancePolicy.Operation.PAIR_FUSION,
                Optional.empty(), inputs, Optional.empty(), List.of(output),
                decision(sources, List.of(value(BOOK, OUTPUT_BOOK, "0.3"))));
    }

    private static BookLifecycleRequest.Equipment equipment(long revision, int imageSeed) {
        return new BookLifecycleRequest.Equipment(EQUIPMENT, revision, revision + 1,
                slot(4), image(imageSeed), image(imageSeed + 1));
    }

    private static BookLifecycleRequest.InventorySlot slot(int slot) {
        return new BookLifecycleRequest.InventorySlot(ACTOR, "player-storage", slot);
    }

    private static BookLifecycleRequest.ItemImage image(int value) {
        return new BookLifecycleRequest.ItemImage(new byte[]{(byte) value});
    }

    private static BookLifecycleRequest.ValueEntry value(BookLifecycleRequest.LineageKind kind, UUID id,
                                                         String value) {
        return new BookLifecycleRequest.ValueEntry(new BookLifecycleRequest.LineageNode(kind, id, FORTUNE),
                new BigDecimal(value));
    }

    private static BookLifecycleRequest.LineageDecision decision(
            List<BookLifecycleRequest.ValueEntry> sources, List<BookLifecycleRequest.ValueEntry> destinations) {
        return new BookLifecycleRequest.LineageDecision("test:mechanics", "not product authorization",
                sources, destinations, BigDecimal.ZERO);
    }

    private static void usingPermitted(BookLifecycleTransaction.Phase current,
                                       BookLifecycleTransaction.Phase next) {
        assertTrue(current.permits(next), current + " -> " + next);
        assertDoesNotThrow(() -> new BookLifecycleTransaction.Advance(
                OPERATION, "a".repeat(64), current, next));
    }
}
