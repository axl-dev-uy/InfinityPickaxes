package com.infinitygear.persistence;

import com.infinitygear.api.v1.BookLedger;
import com.infinitygear.api.v1.BookLifecycleRequest;
import com.infinitygear.api.v1.BookLifecycleTransaction;
import com.infinitygear.api.v1.ProvenancePolicy;
import com.infinitygear.api.v1.ProvenanceTransition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.infinitygear.api.v1.BookLifecycleRequest.InputKind.ARCHIVE_BOOK;
import static com.infinitygear.api.v1.BookLifecycleRequest.LineageKind.ATTACHMENT;
import static com.infinitygear.api.v1.BookLifecycleRequest.LineageKind.BOOK;
import static com.infinitygear.api.v1.BookLifecycleTransaction.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only an explicitly supplied disposable database. Rows are uniquely scoped and retained for inspection. */
class MariaBookLifecycleTransactionIntegrationTest {
    private DataSource source;
    private MariaBookLedger books;
    private MariaBookLifecycleTransaction lifecycle;

    @BeforeEach
    void database() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set INFINITYGEAR_TEST_JDBC_URL to a disposable MariaDB database");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        books = new MariaBookLedger(source);
        lifecycle = new MariaBookLifecycleTransaction(source);
        lifecycle.migrate();
        lifecycle.migrate();
    }

    @Test
    void migrationRerunsAndPreparationPersistsTheCompleteRecoverableRequestWithoutMutation() throws Exception {
        var sourceBook = sourceBook("0.123456789012345678", 41);
        var request = application(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                sourceBook, 41, 42, "0.123456789012345678");

        var prepared = lifecycle.prepare(request);
        assertEquals(PREPARED, prepared.phase());
        assertEquals(request, prepared.request());
        assertEquals(request, new MariaBookLifecycleTransaction(source).find(request.operationId()).orElseThrow().request());
        assertEquals(Optional.of(0L), lifecycle.equipmentRevision(request.equipment().orElseThrow().equipmentId()));
        assertTrue(lifecycle.attachment(request.equipment().orElseThrow().equipmentId(), fortune()).isEmpty());
        assertFalse(books.find(sourceBook.bookId()).orElseThrow().consumed());

        try (var connection = source.getConnection()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM infinitygear_schema_migrations WHERE version=10"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM infinitygear_book_lifecycle_participants WHERE operation_id='"
                    + request.operationId() + "'"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM infinitygear_book_lifecycle_lineage WHERE operation_id='"
                    + request.operationId() + "'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM infinitygear_books WHERE operation_id='"
                    + request.operationId() + "'"));
        }
        assertArrayEquals(request.equipment().orElseThrow().beforeImage().serializedItem(),
                prepared.request().equipment().orElseThrow().beforeImage().serializedItem());
        assertArrayEquals(request.equipment().orElseThrow().afterImage().serializedItem(),
                prepared.request().equipment().orElseThrow().afterImage().serializedItem());
        assertFalse(prepared.updatedAt().isBefore(prepared.preparedAt()));
    }

    @Test
    void finalizationRetiresSourcesWritesFreshArtifactsAndAdvancesAttachmentRevisionOnce() throws Exception {
        var sourceBook = sourceBook("0.100000000000000001", 51);
        var request = application(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                sourceBook, 51, 52, "0.100000000000000001");
        var prepared = lifecycle.prepare(request);
        advance(prepared, CUSTODY_MARKED);
        advance(request, CUSTODY_MARKED, SOURCES_REMOVED);
        advance(request, SOURCES_REMOVED, EQUIPMENT_MUTATED);
        var finalized = advance(request, EQUIPMENT_MUTATED, FINALIZED);

        assertEquals(FINALIZED, finalized.phase());
        assertTrue(books.find(sourceBook.bookId()).orElseThrow().consumed());
        var attachment = lifecycle.attachment(request.equipment().orElseThrow().equipmentId(), fortune()).orElseThrow();
        assertEquals(new BigDecimal("0.100000000000000001"), attachment.sourceValue());
        assertEquals(1, attachment.equipmentRevision());
        assertEquals(request.operationId(), attachment.operationId());

        var replay = lifecycle.advance(new BookLifecycleTransaction.Advance(request.operationId(),
                request.fingerprint(), EQUIPMENT_MUTATED, FINALIZED));
        assertEquals(finalized, replay);
        assertEquals(Optional.of(1L), lifecycle.equipmentRevision(attachment.equipmentId()));
        assertEquals(ACKNOWLEDGED, advance(request, FINALIZED, ACKNOWLEDGED).phase());
    }

    @Test
    void removalTransfersExactAttachmentValueToTheDeclaredBookAndArtifact() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID equipment = UUID.randomUUID();
        var sourceBook = sourceBook("99999999999999999999.123456789012345678", 61);
        var apply = application(UUID.randomUUID(), actor, equipment, 0, sourceBook, 61, 62,
                "99999999999999999999.123456789012345678");
        finalizeApplication(apply);

        UUID outputId = UUID.randomUUID();
        var output = new BookLifecycleRequest.Output(outputId, fortune(), 1, slot(actor, 8), image(64));
        var remove = new BookLifecycleRequest(1, UUID.randomUUID(), actor, ProvenancePolicy.Operation.REMOVE,
                Optional.of(equipment(equipment, actor, 1, 62, 63)), List.of(),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(fortune(), 1, 0, true, false)),
                List.of(output), decision(
                        List.of(value(ATTACHMENT, equipment, "99999999999999999999.123456789012345678")),
                        List.of(value(BOOK, outputId, "99999999999999999999.123456789012345678")), "0"));

        var prepared = lifecycle.prepare(remove);
        assertTrue(books.find(outputId).isEmpty());
        try (var connection = source.getConnection()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM infinitygear_book_lifecycle_outputs WHERE book_id='"
                    + outputId + "' AND source_value=99999999999999999999.123456789012345678"));
        }
        advance(prepared, CUSTODY_MARKED);
        advance(remove, CUSTODY_MARKED, EQUIPMENT_MUTATED);
        advance(remove, EQUIPMENT_MUTATED, OUTPUTS_INSERTED);
        advance(remove, OUTPUTS_INSERTED, FINALIZED);

        assertTrue(lifecycle.attachment(equipment, fortune()).isEmpty());
        assertEquals(Optional.of(2L), lifecycle.equipmentRevision(equipment));
        var receipt = books.find(outputId).orElseThrow();
        assertEquals(new BigDecimal("99999999999999999999.123456789012345678"), receipt.issue().sourceValue());
        assertArrayEquals(output.canonicalItem().serializedItem(), books.load(outputId).orElseThrow());
    }

    @Test
    void ordinaryFusionParticipantContributesNoArchiveValueAndTrackedOutputIsFresh() throws Exception {
        UUID actor = UUID.randomUUID();
        var tracked = sourceBook("0.333333333333333333", 65);
        UUID outputId = UUID.randomUUID();
        var trackedInput = new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(tracked.bookId()),
                slot(actor, 1), 1, 1, image(65));
        var ordinaryInput = new BookLifecycleRequest.Input(BookLifecycleRequest.InputKind.ORDINARY_BOOK,
                Optional.empty(), slot(actor, 2), 16, 1, image(66));
        var output = new BookLifecycleRequest.Output(outputId, fortune(), 2, slot(actor, 1), image(67));
        var request = new BookLifecycleRequest(1, UUID.randomUUID(), actor, ProvenancePolicy.Operation.PAIR_FUSION,
                Optional.empty(), List.of(trackedInput, ordinaryInput), Optional.empty(), List.of(output),
                decision(List.of(value(BOOK, tracked.bookId(), "0.333333333333333333")),
                        List.of(value(BOOK, outputId, "0.333333333333333333")), "0"));

        lifecycle.prepare(request);
        advance(request, PREPARED, CUSTODY_MARKED);
        advance(request, CUSTODY_MARKED, SOURCES_REMOVED);
        advance(request, SOURCES_REMOVED, OUTPUTS_INSERTED);
        advance(request, OUTPUTS_INSERTED, FINALIZED);

        assertTrue(books.find(tracked.bookId()).orElseThrow().consumed());
        assertEquals(new BigDecimal("0.333333333333333333"),
                books.find(outputId).orElseThrow().issue().sourceValue());
        assertArrayEquals(image(67).serializedItem(), books.load(outputId).orElseThrow());
        try (var connection = source.getConnection()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM infinitygear_book_lifecycle_lineage "
                    + "WHERE operation_id='" + request.operationId() + "' AND direction='SOURCE'"));
        }
    }

    @Test
    void concurrentIdenticalReplaySucceedsAndConflictingPayloadsReject() throws Exception {
        var sourceBook = sourceBook("1", 71);
        UUID operation = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID equipment = UUID.randomUUID();
        var original = application(operation, actor, equipment, 0, sourceBook, 71, 72, "1");
        var conflict = application(operation, actor, equipment, 0, sourceBook, 71, 73, "1");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var duplicates = new ArrayList<Future<BookLifecycleTransaction.State>>();
            for (int i = 0; i < 12; i++) duplicates.add(executor.submit(() -> lifecycle.prepare(original)));
            for (var duplicate : duplicates) assertEquals(original, duplicate.get().request());

            var conflicts = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) conflicts.add(executor.submit(() -> {
                try { lifecycle.prepare(conflict); return false; }
                catch (IllegalArgumentException expected) { return true; }
            }));
            for (var rejected : conflicts) assertTrue(rejected.get());
        }
        assertEquals(original, lifecycle.find(operation).orElseThrow().request());
    }

    @Test
    void staleAttachmentRevisionAndSharedOperationIdCollisionsRejectWithoutMutation() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID equipment = UUID.randomUUID();
        var firstSource = sourceBook("2", 81);
        var first = application(UUID.randomUUID(), actor, equipment, 0, firstSource, 81, 82, "2");
        finalizeApplication(first);

        var staleSource = sourceBook("3", 83);
        var stale = application(UUID.randomUUID(), actor, equipment, 0, staleSource, 83, 84, "3");
        assertThrows(IllegalArgumentException.class, () -> lifecycle.prepare(stale));
        assertFalse(books.find(staleSource.bookId()).orElseThrow().consumed());
        assertEquals(Optional.of(1L), lifecycle.equipmentRevision(equipment));

        UUID issuanceOperation = UUID.randomUUID();
        books.issue(issue(issuanceOperation, UUID.randomUUID(), "1"));
        var collisionSource = sourceBook("4", 85);
        assertThrows(IllegalArgumentException.class, () -> lifecycle.prepare(application(issuanceOperation,
                UUID.randomUUID(), UUID.randomUUID(), 0, collisionSource, 85, 86, "4")));

        UUID legacyOperation = UUID.randomUUID();
        var legacySource = sourceBook("5", 87);
        books.transition(new ProvenanceTransition.Request(legacyOperation, ProvenancePolicy.Operation.REMOVE,
                        List.of(legacySource.bookId()), List.of()),
                (operation, sources, count) -> new ProvenancePolicy.Decision(true, List.of(), "test dispose"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.prepare(application(legacyOperation,
                UUID.randomUUID(), UUID.randomUUID(), 0, collisionSource, 85, 86, "4")));

        UUID lifecycleOperation = UUID.randomUUID();
        var lifecycleRequest = application(lifecycleOperation, UUID.randomUUID(), UUID.randomUUID(), 0,
                collisionSource, 85, 86, "4");
        lifecycle.prepare(lifecycleRequest);
        assertThrows(IllegalArgumentException.class, () -> books.issue(issue(lifecycleOperation, UUID.randomUUID(), "1")));
        assertThrows(IllegalArgumentException.class, () -> books.transition(
                new ProvenanceTransition.Request(lifecycleOperation, ProvenancePolicy.Operation.REMOVE,
                        List.of(collisionSource.bookId()), List.of()),
                (operation, sources, count) -> new ProvenancePolicy.Decision(true, List.of(), "test dispose")));
    }

    @Test
    void failedFinalizationRollsBackEveryAuthorityAndOnlyThenAllowsVerifiedRollback() throws Exception {
        var sourceBook = sourceBook("7.000000000000000009", 91);
        var request = application(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                sourceBook, 91, 92, "7.000000000000000009");
        var failing = new MariaBookLifecycleTransaction(source, connection -> {
            throw new SQLExceptionForTest("injected before commit");
        });
        var prepared = failing.prepare(request);
        failing.advance(new BookLifecycleTransaction.Advance(request.operationId(), request.fingerprint(), PREPARED, CUSTODY_MARKED));
        failing.advance(new BookLifecycleTransaction.Advance(request.operationId(), request.fingerprint(), CUSTODY_MARKED, SOURCES_REMOVED));
        failing.advance(new BookLifecycleTransaction.Advance(request.operationId(), request.fingerprint(), SOURCES_REMOVED, EQUIPMENT_MUTATED));
        assertThrows(SQLExceptionForTest.class, () -> failing.advance(new BookLifecycleTransaction.Advance(
                request.operationId(), request.fingerprint(), EQUIPMENT_MUTATED, FINALIZED)));

        assertEquals(EQUIPMENT_MUTATED, lifecycle.find(request.operationId()).orElseThrow().phase());
        assertFalse(books.find(sourceBook.bookId()).orElseThrow().consumed());
        assertTrue(lifecycle.attachment(request.equipment().orElseThrow().equipmentId(), fortune()).isEmpty());
        assertEquals(Optional.of(0L), lifecycle.equipmentRevision(request.equipment().orElseThrow().equipmentId()));
        assertEquals(ROLLED_BACK, lifecycle.advance(new BookLifecycleTransaction.Advance(request.operationId(),
                request.fingerprint(), EQUIPMENT_MUTATED, ROLLED_BACK)).phase());
        assertThrows(IllegalArgumentException.class, () -> lifecycle.advance(new BookLifecycleTransaction.Advance(
                request.operationId(), request.fingerprint(), ROLLED_BACK, FINALIZED)));
    }

    @Test
    void phaseCasRejectsSkippedApplicablePhasesAndConcurrentConflicts() throws Exception {
        var sourceBook = sourceBook("8", 101);
        var request = application(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                sourceBook, 101, 102, "8");
        lifecycle.prepare(request);
        assertThrows(IllegalArgumentException.class, () -> advance(request, PREPARED, SOURCES_REMOVED));
        advance(request, PREPARED, CUSTODY_MARKED);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<BookLifecycleTransaction.State> first = executor.submit(() -> advance(request, CUSTODY_MARKED, SOURCES_REMOVED));
            Future<BookLifecycleTransaction.State> second = executor.submit(() -> advance(request, CUSTODY_MARKED, SOURCES_REMOVED));
            assertEquals(SOURCES_REMOVED, first.get().phase());
            assertEquals(SOURCES_REMOVED, second.get().phase());
        }
        assertThrows(IllegalArgumentException.class, () -> advance(request, PREPARED, SOURCES_REMOVED));
        assertThrows(IllegalStateException.class, () -> advance(request, PREPARED, CUSTODY_MARKED));
    }

    private BookLedger.Receipt sourceBook(String value, int image) throws Exception {
        var receipt = books.issue(issue(UUID.randomUUID(), UUID.randomUUID(), value));
        books.saveFirst(receipt, image(image).serializedItem());
        return receipt;
    }

    private static BookLedger.Issue issue(UUID operation, UUID reward, String value) {
        return new BookLedger.Issue(operation, reward, fortune(), 1, "archive:test", new BigDecimal(value));
    }

    private void finalizeApplication(BookLifecycleRequest request) throws Exception {
        lifecycle.prepare(request);
        advance(request, PREPARED, CUSTODY_MARKED);
        advance(request, CUSTODY_MARKED, SOURCES_REMOVED);
        advance(request, SOURCES_REMOVED, EQUIPMENT_MUTATED);
        advance(request, EQUIPMENT_MUTATED, FINALIZED);
    }

    private BookLifecycleTransaction.State advance(BookLifecycleTransaction.State state,
                                                    BookLifecycleTransaction.Phase next) throws Exception {
        return advance(state.request(), state.phase(), next);
    }

    private BookLifecycleTransaction.State advance(BookLifecycleRequest request,
                                                    BookLifecycleTransaction.Phase expected,
                                                    BookLifecycleTransaction.Phase next) throws Exception {
        return lifecycle.advance(new BookLifecycleTransaction.Advance(request.operationId(), request.fingerprint(), expected, next));
    }

    private static BookLifecycleRequest application(UUID operation, UUID actor, UUID equipment, long revision,
                                                    BookLedger.Receipt source, int before, int after, String value) {
        var physical = new BookLifecycleRequest.Input(ARCHIVE_BOOK, Optional.of(source.bookId()), slot(actor, 5),
                1, 1, image(before));
        return new BookLifecycleRequest(1, operation, actor, ProvenancePolicy.Operation.APPLY,
                Optional.of(equipment(equipment, actor, revision, before + 20, after + 20)), List.of(physical),
                Optional.of(new BookLifecycleRequest.EnchantmentChange(fortune(), 0, 1, false, true)), List.of(),
                decision(List.of(value(BOOK, source.bookId(), value)),
                        List.of(value(ATTACHMENT, equipment, value)), "0"));
    }

    private static BookLifecycleRequest.Equipment equipment(UUID equipment, UUID actor, long revision,
                                                            int before, int after) {
        return new BookLifecycleRequest.Equipment(equipment, revision, revision + 1, slot(actor, 4),
                image(before), image(after));
    }

    private static BookLifecycleRequest.LineageDecision decision(List<BookLifecycleRequest.ValueEntry> sources,
                                                                 List<BookLifecycleRequest.ValueEntry> destinations,
                                                                 String disposed) {
        return new BookLifecycleRequest.LineageDecision("test:journal-mechanics", "test policy only",
                sources, destinations, new BigDecimal(disposed));
    }

    private static BookLifecycleRequest.ValueEntry value(BookLifecycleRequest.LineageKind kind, UUID id, String value) {
        return new BookLifecycleRequest.ValueEntry(new BookLifecycleRequest.LineageNode(kind, id, fortune()),
                new BigDecimal(value));
    }

    private static BookLifecycleRequest.InventorySlot slot(UUID actor, int slot) {
        return new BookLifecycleRequest.InventorySlot(actor, "player-storage", slot);
    }

    private static BookLifecycleRequest.ItemImage image(int value) {
        return new BookLifecycleRequest.ItemImage(new byte[]{(byte) value});
    }

    private static String fortune() { return "minecraft:fortune"; }

    private static int count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static final class SQLExceptionForTest extends Exception {
        private SQLExceptionForTest(String message) { super(message); }
    }
}
