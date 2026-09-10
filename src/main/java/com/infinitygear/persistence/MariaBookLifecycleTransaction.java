package com.infinitygear.persistence;

import com.infinitygear.api.v1.BookLedger;
import com.infinitygear.api.v1.BookLifecycleRequest;
import com.infinitygear.api.v1.BookLifecycleTransaction;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking MariaDB journal and attachment repository for the lifecycle contract.
 * This class deliberately has no Bukkit participant and is not registered as a service.
 */
public final class MariaBookLifecycleTransaction implements BookLifecycleTransaction {
    private static final String OPERATIONS = "infinitygear_book_lifecycle_operations";
    private final DataSource source;
    private final FinalizationHook finalizationHook;

    public MariaBookLifecycleTransaction(DataSource source) {
        this(source, connection -> { });
    }

    MariaBookLifecycleTransaction(DataSource source, FinalizationHook finalizationHook) {
        this.source = Objects.requireNonNull(source, "source");
        this.finalizationHook = Objects.requireNonNull(finalizationHook, "finalizationHook");
    }

    /** Restart-safe global migration 10. Earlier book tables are prerequisites and are also restart-safe. */
    public void migrate() throws SQLException {
        new MariaBookLedger(source).migrate();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_equipment_attachment_revisions ("
                    + "equipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "revision BIGINT NOT NULL, updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) "
                    + "ON UPDATE CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_equipment_attachments ("
                    + "equipment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "enchantment_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "source_value DECIMAL(38,18) NOT NULL,"
                    + "operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "PRIMARY KEY(equipment_id,enchantment_key),"
                    + "FOREIGN KEY(equipment_id) REFERENCES infinitygear_equipment_attachment_revisions(equipment_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + OPERATIONS + " ("
                    + "operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "schema_version INT NOT NULL, actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "operation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "phase VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "previous_phase VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "policy_reference VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                    + "policy_reason VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                    + "disposed_value DECIMAL(38,18) NOT NULL, request_payload LONGBLOB NOT NULL,"
                    + "prepared_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "FOREIGN KEY(operation_id) REFERENCES infinitygear_book_operations(operation_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_lifecycle_participants ("
                    + "operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "participant_index INT NOT NULL, participant_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "tracked_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "holder_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "inventory_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, slot_index INT NOT NULL,"
                    + "expected_amount INT NULL, consumed_amount INT NULL, expected_revision BIGINT NULL, resulting_revision BIGINT NULL,"
                    + "before_image MEDIUMBLOB NOT NULL, after_image MEDIUMBLOB NULL,"
                    + "PRIMARY KEY(operation_id,participant_index),"
                    + "FOREIGN KEY(operation_id) REFERENCES " + OPERATIONS + "(operation_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_lifecycle_outputs ("
                    + "operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, output_index INT NOT NULL,"
                    + "book_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,"
                    + "enchantment_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, enchantment_level INT NOT NULL,"
                    + "holder_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "inventory_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, slot_index INT NOT NULL,"
                    + "source_value DECIMAL(38,18) NOT NULL, canonical_item MEDIUMBLOB NOT NULL,"
                    + "PRIMARY KEY(operation_id,output_index),"
                    + "FOREIGN KEY(operation_id) REFERENCES " + OPERATIONS + "(operation_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_lifecycle_lineage ("
                    + "operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "direction VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "node_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "node_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "enchantment_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "source_value DECIMAL(38,18) NOT NULL,"
                    + "PRIMARY KEY(operation_id,direction,node_kind,node_id,enchantment_key),"
                    + "FOREIGN KEY(operation_id) REFERENCES " + OPERATIONS + "(operation_id)) ENGINE=InnoDB");
            statement.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (10)");
        }
    }

    @Override
    public State prepare(BookLifecycleRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        for (int attempt = 0; ; attempt++) {
            try {
                return prepareOnce(request);
            } catch (SQLException conflict) {
                // INSERT IGNORE plus foreign-key checks can deadlock under a burst of first-writer
                // duplicates. Preparation is pre-physical-mutation and the failed transaction is
                // positively rolled back, so a small bounded database retry is safe.
                if ((conflict.getErrorCode() != 1213 && conflict.getErrorCode() != 1205) || attempt == 15) throw conflict;
            }
        }
    }

    private State prepareOnce(BookLifecycleRequest request) throws Exception {
        String fingerprint = request.fingerprint();
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Optional<State> visible = find(connection, request.operationId(), false);
                if (visible.isPresent()) {
                    State replay = visible.orElseThrow().requireReplay(request);
                    connection.commit();
                    return replay;
                }
                int claimed;
                try (var insert = connection.prepareStatement("INSERT IGNORE INTO infinitygear_book_operations"
                        + "(operation_id,fingerprint,completed) VALUES (?,?,FALSE)")) {
                    insert.setString(1, request.operationId().toString());
                    insert.setString(2, fingerprint);
                    claimed = insert.executeUpdate();
                }
                lockOperationNamespace(connection, request.operationId(), fingerprint);
                Optional<State> existing = find(connection, request.operationId(), true);
                if (existing.isPresent()) {
                    State replay = existing.orElseThrow().requireReplay(request);
                    connection.commit();
                    return replay;
                }
                if (claimed != 1) {
                    throw new IllegalArgumentException("Operation ID belongs to issuance or an older ledger transition");
                }

                validateBeforeState(connection, request, true);
                insertOperation(connection, request, fingerprint);
                insertParticipants(connection, request);
                insertOutputs(connection, request);
                insertLineage(connection, request);
                State result = find(connection, request.operationId(), false).orElseThrow();
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override
    public Optional<State> find(UUID operationId) throws Exception {
        Objects.requireNonNull(operationId, "operationId");
        try (var connection = source.getConnection()) {
            return find(connection, operationId, false);
        }
    }

    @Override
    public State advance(Advance advance) throws Exception {
        Objects.requireNonNull(advance, "advance");
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                State current = find(connection, advance.operationId(), true)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown lifecycle operation"));
                if (!current.fingerprint().equals(advance.fingerprint())) {
                    throw new IllegalArgumentException("Lifecycle fingerprint mismatch");
                }
                if (current.phase() == advance.next()) {
                    if (advance.expected() != advance.next()) {
                        validateTransition(current.request(), advance.expected(), advance.next());
                        if (previousPhase(connection, advance.operationId()).orElse(null) != advance.expected()) {
                            throw new IllegalStateException("Lifecycle phase replay does not match the committed transition");
                        }
                    }
                    connection.commit();
                    return current;
                }
                if (current.phase() != advance.expected()) {
                    throw new IllegalStateException("Lifecycle phase compare-and-set conflict");
                }
                validateTransition(current.request(), advance.expected(), advance.next());
                if (advance.next() == Phase.FINALIZED) {
                    finalizeRequest(connection, current.request());
                    finalizationHook.beforeCommit(connection);
                } else if (advance.next() == Phase.ABORTED || advance.next() == Phase.ROLLED_BACK) {
                    // The future physical participant may assert restoration only after comparing the saved
                    // before-images. The journal additionally proves that its durable authorities are unchanged.
                    validateBeforeState(connection, current.request(), false);
                }
                try (var update = connection.prepareStatement("UPDATE " + OPERATIONS
                        + " SET previous_phase=phase,phase=?,updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE operation_id=? AND fingerprint=? AND phase=?")) {
                    update.setString(1, advance.next().name());
                    update.setString(2, advance.operationId().toString());
                    update.setString(3, advance.fingerprint());
                    update.setString(4, advance.expected().name());
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Lifecycle phase compare-and-set conflict");
                }
                State result = find(connection, advance.operationId(), false).orElseThrow();
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public Optional<Long> equipmentRevision(UUID equipmentId) throws SQLException {
        Objects.requireNonNull(equipmentId, "equipmentId");
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT revision FROM infinitygear_equipment_attachment_revisions WHERE equipment_id=?")) {
            query.setString(1, equipmentId.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(rows.getLong(1)) : Optional.empty(); }
        }
    }

    public Optional<Attachment> attachment(UUID equipmentId, String enchantmentKey) throws SQLException {
        Objects.requireNonNull(equipmentId, "equipmentId");
        Objects.requireNonNull(enchantmentKey, "enchantmentKey");
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT a.source_value,a.operation_id,r.revision FROM infinitygear_equipment_attachments a "
                        + "JOIN infinitygear_equipment_attachment_revisions r ON r.equipment_id=a.equipment_id "
                        + "WHERE a.equipment_id=? AND a.enchantment_key=?")) {
            query.setString(1, equipmentId.toString());
            query.setString(2, enchantmentKey);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(new Attachment(equipmentId, enchantmentKey,
                        rows.getBigDecimal(1), UUID.fromString(rows.getString(2)), rows.getLong(3))) : Optional.empty();
            }
        }
    }

    public record Attachment(UUID equipmentId, String enchantmentKey, BigDecimal sourceValue,
                             UUID operationId, long equipmentRevision) {
        public Attachment {
            Objects.requireNonNull(equipmentId);
            Objects.requireNonNull(enchantmentKey);
            sourceValue = BookLedger.exactValue(sourceValue);
            Objects.requireNonNull(operationId);
            if (equipmentRevision < 0) throw new IllegalArgumentException("Negative equipment attachment revision");
        }
    }

    private void finalizeRequest(Connection connection, BookLifecycleRequest request) throws Exception {
        validateBeforeState(connection, request, true);
        Map<BookLifecycleRequest.LineageNode, BigDecimal> destinations = values(request.lineageDecision().destinations());

        for (var input : request.inputs()) {
            if (input.kind() != BookLifecycleRequest.InputKind.ARCHIVE_BOOK) continue;
            UUID id = input.bookId().orElseThrow();
            try (var update = connection.prepareStatement(
                    "UPDATE infinitygear_books SET consumed=TRUE WHERE book_id=? AND consumed=FALSE")) {
                update.setString(1, id.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Source retirement conflict");
            }
            try (var lineage = connection.prepareStatement(
                    "INSERT INTO infinitygear_book_lineage(operation_id,parent_id) VALUES (?,?)")) {
                lineage.setString(1, request.operationId().toString());
                lineage.setString(2, id.toString());
                lineage.executeUpdate();
            }
        }

        for (var output : request.outputs()) {
            var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.BOOK,
                    output.bookId(), output.enchantmentKey());
            BigDecimal value = requireValue(destinations, node, "output");
            try (var insert = connection.prepareStatement("INSERT INTO infinitygear_books"
                    + "(book_id,operation_id,reward_id,enchantment_key,enchantment_level,provenance_reference,source_value,consumed) "
                    + "VALUES (?,?,?,?,?,?,?,FALSE)")) {
                insert.setString(1, output.bookId().toString());
                insert.setString(2, request.operationId().toString());
                insert.setString(3, output.bookId().toString());
                insert.setString(4, output.enchantmentKey());
                insert.setInt(5, output.level());
                insert.setString(6, request.lineageDecision().policyReference());
                insert.setBigDecimal(7, value);
                insert.executeUpdate();
            }
            try (var artifact = connection.prepareStatement(
                    "INSERT INTO infinitygear_book_artifacts(book_id,serialized_item) VALUES (?,?)")) {
                artifact.setString(1, output.bookId().toString());
                artifact.setBytes(2, output.canonicalItem().serializedItem());
                artifact.executeUpdate();
            }
        }

        if (request.equipment().isPresent()) {
            var equipment = request.equipment().orElseThrow();
            var change = request.enchantmentChange().orElseThrow();
            if (change.archiveAttachmentBefore()) {
                try (var delete = connection.prepareStatement("DELETE FROM infinitygear_equipment_attachments "
                        + "WHERE equipment_id=? AND enchantment_key=?")) {
                    delete.setString(1, equipment.equipmentId().toString());
                    delete.setString(2, change.enchantmentKey());
                    if (delete.executeUpdate() != 1) throw new SQLException("Attachment removal conflict");
                }
            }
            if (change.archiveAttachmentAfter()) {
                var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.ATTACHMENT,
                        equipment.equipmentId(), change.enchantmentKey());
                try (var insert = connection.prepareStatement("INSERT INTO infinitygear_equipment_attachments"
                        + "(equipment_id,enchantment_key,source_value,operation_id) VALUES (?,?,?,?)")) {
                    insert.setString(1, equipment.equipmentId().toString());
                    insert.setString(2, change.enchantmentKey());
                    insert.setBigDecimal(3, requireValue(destinations, node, "attachment"));
                    insert.setString(4, request.operationId().toString());
                    insert.executeUpdate();
                }
            }
            try (var update = connection.prepareStatement("UPDATE infinitygear_equipment_attachment_revisions "
                    + "SET revision=? WHERE equipment_id=? AND revision=?")) {
                update.setLong(1, equipment.resultingRevision());
                update.setString(2, equipment.equipmentId().toString());
                update.setLong(3, equipment.expectedRevision());
                if (update.executeUpdate() != 1) throw new SQLException("Equipment attachment revision conflict");
            }
        }
        try (var completed = connection.prepareStatement(
                "UPDATE infinitygear_book_operations SET completed=TRUE WHERE operation_id=? AND completed=FALSE")) {
            completed.setString(1, request.operationId().toString());
            if (completed.executeUpdate() != 1) throw new SQLException("Lifecycle finalization conflict");
        }
    }

    private void validateBeforeState(Connection connection, BookLifecycleRequest request, boolean lock) throws SQLException {
        Map<BookLifecycleRequest.LineageNode, BigDecimal> sources = values(request.lineageDecision().sources());
        List<BookLifecycleRequest.Input> tracked = request.inputs().stream()
                .filter(input -> input.kind() == BookLifecycleRequest.InputKind.ARCHIVE_BOOK)
                .sorted(Comparator.comparing(input -> input.bookId().orElseThrow().toString())).toList();
        for (var input : tracked) {
            UUID id = input.bookId().orElseThrow();
            String sql = "SELECT b.enchantment_key,b.source_value,b.consumed,a.serialized_item "
                    + "FROM infinitygear_books b LEFT JOIN infinitygear_book_artifacts a ON a.book_id=b.book_id "
                    + "WHERE b.book_id=?" + (lock ? " FOR UPDATE" : "");
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getBoolean("consumed")) throw new IllegalArgumentException("Unknown or consumed lifecycle source book");
                    var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.BOOK,
                            id, rows.getString("enchantment_key"));
                    if (rows.getBigDecimal("source_value").compareTo(requireValue(sources, node, "source book")) != 0) {
                        throw new IllegalArgumentException("Lifecycle source book value mismatch");
                    }
                    byte[] artifact = rows.getBytes("serialized_item");
                    if (artifact == null || !java.util.Arrays.equals(artifact, input.beforeImage().serializedItem())) {
                        throw new IllegalArgumentException("Lifecycle source book artifact mismatch");
                    }
                }
            }
        }

        for (var output : request.outputs()) {
            try (var query = connection.prepareStatement("SELECT book_id FROM infinitygear_books WHERE book_id=?"
                    + (lock ? " FOR UPDATE" : ""))) {
                query.setString(1, output.bookId().toString());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) throw new IllegalArgumentException("Lifecycle output identity is not fresh");
                }
            }
        }

        if (request.equipment().isEmpty()) return;
        var equipment = request.equipment().orElseThrow();
        if (equipment.expectedRevision() == 0) {
            try (var insert = connection.prepareStatement("INSERT IGNORE INTO infinitygear_equipment_attachment_revisions"
                    + "(equipment_id,revision) VALUES (?,0)")) {
                insert.setString(1, equipment.equipmentId().toString());
                insert.executeUpdate();
            }
        }
        try (var revision = connection.prepareStatement("SELECT revision FROM infinitygear_equipment_attachment_revisions "
                + "WHERE equipment_id=?" + (lock ? " FOR UPDATE" : ""))) {
            revision.setString(1, equipment.equipmentId().toString());
            try (var rows = revision.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != equipment.expectedRevision()) {
                    throw new IllegalArgumentException("Stale equipment attachment revision");
                }
            }
        }
        var change = request.enchantmentChange().orElseThrow();
        try (var attachment = connection.prepareStatement("SELECT source_value FROM infinitygear_equipment_attachments "
                + "WHERE equipment_id=? AND enchantment_key=?" + (lock ? " FOR UPDATE" : ""))) {
            attachment.setString(1, equipment.equipmentId().toString());
            attachment.setString(2, change.enchantmentKey());
            try (var rows = attachment.executeQuery()) {
                boolean present = rows.next();
                if (present != change.archiveAttachmentBefore()) {
                    throw new IllegalArgumentException("Equipment attachment lineage mismatch");
                }
                if (present) {
                    var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.ATTACHMENT,
                            equipment.equipmentId(), change.enchantmentKey());
                    if (rows.getBigDecimal(1).compareTo(requireValue(sources, node, "source attachment")) != 0) {
                        throw new IllegalArgumentException("Equipment attachment value mismatch");
                    }
                }
            }
        }
    }

    private static void validateTransition(BookLifecycleRequest request, Phase current, Phase next) {
        if (!current.permits(next)) throw new IllegalArgumentException("Invalid lifecycle phase transition");
        if (next == Phase.ABORTED || next == Phase.ROLLED_BACK) return;
        List<Phase> required = new ArrayList<>();
        required.add(Phase.PREPARED);
        required.add(Phase.CUSTODY_MARKED);
        if (!request.inputs().isEmpty()) required.add(Phase.SOURCES_REMOVED);
        if (request.equipment().isPresent()) required.add(Phase.EQUIPMENT_MUTATED);
        if (!request.outputs().isEmpty()) required.add(Phase.OUTPUTS_INSERTED);
        required.add(Phase.FINALIZED);
        required.add(Phase.ACKNOWLEDGED);
        int currentIndex = required.indexOf(current);
        int nextIndex = required.indexOf(next);
        if (currentIndex < 0 || nextIndex != currentIndex + 1) {
            throw new IllegalArgumentException("Lifecycle transition skips an applicable physical phase");
        }
    }

    private static void lockOperationNamespace(Connection connection, UUID operationId, String fingerprint) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT fingerprint FROM infinitygear_book_operations WHERE operation_id=? FOR UPDATE")) {
            query.setString(1, operationId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !fingerprint.equals(rows.getString(1))) {
                    throw new IllegalArgumentException("Operation ID reused with a conflicting payload or operation kind");
                }
            }
        }
    }

    private static void insertOperation(Connection connection, BookLifecycleRequest request, String fingerprint) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO " + OPERATIONS
                + "(operation_id,fingerprint,schema_version,actor_id,operation_type,phase,policy_reference,policy_reason,"
                + "disposed_value,request_payload) VALUES (?,?,?,?,?,'PREPARED',?,?,?,?)")) {
            insert.setString(1, request.operationId().toString());
            insert.setString(2, fingerprint);
            insert.setInt(3, request.schemaVersion());
            insert.setString(4, request.actorId().toString());
            insert.setString(5, request.operation().name());
            insert.setString(6, request.lineageDecision().policyReference());
            insert.setString(7, request.lineageDecision().reason());
            insert.setBigDecimal(8, request.lineageDecision().disposedValue());
            insert.setBytes(9, BookLifecycleRequestCodec.encode(request));
            insert.executeUpdate();
        }
    }

    private static void insertParticipants(Connection connection, BookLifecycleRequest request) throws SQLException {
        int index = 0;
        if (request.equipment().isPresent()) {
            var equipment = request.equipment().orElseThrow();
            insertParticipant(connection, request.operationId(), index++, "EQUIPMENT", equipment.equipmentId(),
                    equipment.slot(), null, null, equipment.expectedRevision(), equipment.resultingRevision(),
                    equipment.beforeImage().serializedItem(), equipment.afterImage().serializedItem());
        }
        for (var input : request.inputs()) {
            insertParticipant(connection, request.operationId(), index++, input.kind().name(), input.bookId().orElse(null),
                    input.slot(), input.expectedAmount(), input.consumedAmount(), null, null,
                    input.beforeImage().serializedItem(), null);
        }
    }

    private static void insertParticipant(Connection connection, UUID operationId, int index, String kind, UUID trackedId,
                                          BookLifecycleRequest.InventorySlot slot, Integer expectedAmount,
                                          Integer consumedAmount, Long expectedRevision, Long resultingRevision,
                                          byte[] before, byte[] after) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO infinitygear_book_lifecycle_participants"
                + "(operation_id,participant_index,participant_kind,tracked_id,holder_id,inventory_id,slot_index,"
                + "expected_amount,consumed_amount,expected_revision,resulting_revision,before_image,after_image) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, operationId.toString());
            insert.setInt(2, index);
            insert.setString(3, kind);
            insert.setString(4, trackedId == null ? null : trackedId.toString());
            insert.setString(5, slot.holderId().toString());
            insert.setString(6, slot.inventoryId());
            insert.setInt(7, slot.slot());
            nullableInt(insert, 8, expectedAmount);
            nullableInt(insert, 9, consumedAmount);
            nullableLong(insert, 10, expectedRevision);
            nullableLong(insert, 11, resultingRevision);
            insert.setBytes(12, before);
            insert.setBytes(13, after);
            insert.executeUpdate();
        }
    }

    private static void insertOutputs(Connection connection, BookLifecycleRequest request) throws SQLException {
        Map<BookLifecycleRequest.LineageNode, BigDecimal> values = values(request.lineageDecision().destinations());
        int index = 0;
        for (var output : request.outputs()) {
            var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.BOOK,
                    output.bookId(), output.enchantmentKey());
            try (var insert = connection.prepareStatement("INSERT INTO infinitygear_book_lifecycle_outputs"
                    + "(operation_id,output_index,book_id,enchantment_key,enchantment_level,holder_id,inventory_id,slot_index,"
                    + "source_value,canonical_item) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, request.operationId().toString());
                insert.setInt(2, index++);
                insert.setString(3, output.bookId().toString());
                insert.setString(4, output.enchantmentKey());
                insert.setInt(5, output.level());
                insert.setString(6, output.slot().holderId().toString());
                insert.setString(7, output.slot().inventoryId());
                insert.setInt(8, output.slot().slot());
                insert.setBigDecimal(9, requireValue(values, node, "output reservation"));
                insert.setBytes(10, output.canonicalItem().serializedItem());
                insert.executeUpdate();
            }
        }
    }

    private static void insertLineage(Connection connection, BookLifecycleRequest request) throws SQLException {
        for (var entry : request.lineageDecision().sources()) insertLineage(connection, request.operationId(), "SOURCE", entry);
        for (var entry : request.lineageDecision().destinations()) insertLineage(connection, request.operationId(), "DESTINATION", entry);
    }

    private static void insertLineage(Connection connection, UUID operationId, String direction,
                                      BookLifecycleRequest.ValueEntry entry) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO infinitygear_book_lifecycle_lineage"
                + "(operation_id,direction,node_kind,node_id,enchantment_key,source_value) VALUES (?,?,?,?,?,?)")) {
            insert.setString(1, operationId.toString());
            insert.setString(2, direction);
            insert.setString(3, entry.node().kind().name());
            insert.setString(4, entry.node().identity().toString());
            insert.setString(5, entry.node().enchantmentKey());
            insert.setBigDecimal(6, entry.value());
            insert.executeUpdate();
        }
    }

    private static Optional<State> find(Connection connection, UUID operationId, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT fingerprint,phase,request_payload,prepared_at,updated_at FROM "
                + OPERATIONS + " WHERE operation_id=?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, operationId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                BookLifecycleRequest request = BookLifecycleRequestCodec.decode(rows.getBytes("request_payload"));
                String fingerprint = rows.getString("fingerprint");
                if (!request.operationId().equals(operationId) || !request.fingerprint().equals(fingerprint)) {
                    throw new IllegalStateException("Corrupt lifecycle journal payload");
                }
                return Optional.of(new State(request, fingerprint, Phase.valueOf(rows.getString("phase")),
                        instant(rows, "prepared_at"), instant(rows, "updated_at")));
            }
        }
    }

    private static Optional<Phase> previousPhase(Connection connection, UUID operationId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT previous_phase FROM " + OPERATIONS + " WHERE operation_id=?")) {
            query.setString(1, operationId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Lifecycle operation disappeared while locked");
                String value = rows.getString(1);
                return value == null ? Optional.empty() : Optional.of(Phase.valueOf(value));
            }
        }
    }

    private static Map<BookLifecycleRequest.LineageNode, BigDecimal> values(
            List<BookLifecycleRequest.ValueEntry> entries) {
        var result = new HashMap<BookLifecycleRequest.LineageNode, BigDecimal>();
        for (var entry : entries) result.put(entry.node(), entry.value());
        return result;
    }

    private static BigDecimal requireValue(Map<BookLifecycleRequest.LineageNode, BigDecimal> values,
                                           BookLifecycleRequest.LineageNode node, String subject) {
        BigDecimal value = values.get(node);
        if (value == null) throw new IllegalArgumentException("Missing exact value for lifecycle " + subject);
        return value;
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(column);
        if (timestamp == null) throw new SQLException("Missing lifecycle timestamp");
        return timestamp.toInstant();
    }

    private static void nullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER); else statement.setInt(index, value);
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT); else statement.setLong(index, value);
    }

    @FunctionalInterface
    interface FinalizationHook {
        void beforeCommit(Connection connection) throws Exception;
    }
}
