package com.infinitygear.persistence;

import com.infinitygear.api.v1.BookLedger;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** All tables belong to InfinityGear. Uses JDBC interfaces; driver is provided by Paper. */
public final class MariaBookLedger implements BookLedger, com.infinitygear.api.v1.ProvenanceTransition, com.infinitygear.integration.BookArtifacts {
    private final DataSource dataSource;
    public MariaBookLedger(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    public void migrate() throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            // MariaDB DDL implicitly commits. Each migration is restart-safe, then recorded after completion.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_books (book_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, reward_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, enchantment_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, enchantment_level INT NOT NULL, provenance_reference VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, source_value DECIMAL(38,18) NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE KEY issuance (operation_id,reward_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (1)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_operations (operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, completed BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_lineage (operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, parent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, PRIMARY KEY(operation_id,parent_id), FOREIGN KEY (parent_id) REFERENCES infinitygear_books(book_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (2)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_book_artifacts (book_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, serialized_item MEDIUMBLOB NOT NULL, FOREIGN KEY (book_id) REFERENCES infinitygear_books(book_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (4)");
        }
    }

    @Override public Receipt issue(Issue request) throws SQLException {
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("INSERT INTO infinitygear_book_operations(operation_id,fingerprint) VALUES (?,'ISSUANCE') ON DUPLICATE KEY UPDATE operation_id=operation_id")) {
                    s.setString(1, request.operationId().toString()); s.executeUpdate();
                }
                try (var s = c.prepareStatement("SELECT fingerprint FROM infinitygear_book_operations WHERE operation_id=? FOR UPDATE")) {
                    s.setString(1, request.operationId().toString());
                    try (var r = s.executeQuery()) { if (!r.next() || !"ISSUANCE".equals(r.getString(1))) throw new IllegalArgumentException("Operation ID belongs to a lifecycle transition"); }
                }
                // Serialize concurrent requests through the unique operation/reward constraint.
                try (var s = c.prepareStatement("INSERT INTO infinitygear_books(book_id,operation_id,reward_id,enchantment_key,enchantment_level,provenance_reference,source_value) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE book_id=book_id")) {
                    s.setString(1, UUID.randomUUID().toString()); s.setString(2, request.operationId().toString());
                    s.setString(3, request.rewardId().toString()); s.setString(4, request.enchantmentKey());
                    s.setInt(5, request.level()); s.setString(6, request.provenanceReference()); s.setBigDecimal(7, request.sourceValue());
                    s.executeUpdate();
                }
                Receipt receipt;
                try (var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE operation_id=? AND reward_id=? FOR UPDATE")) {
                    s.setString(1, request.operationId().toString()); s.setString(2, request.rewardId().toString());
                    try (var r = s.executeQuery()) { if (!r.next()) throw new SQLException("Issuance not found"); receipt = read(r); }
                }
                if (!receipt.issue().equals(request)) throw new IllegalArgumentException("Operation/reward reused with different payload");
                c.commit(); return receipt;
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    @Override public Optional<Receipt> find(UUID id) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE book_id=?")) {
            s.setString(1, id.toString());
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    @Override public Optional<byte[]> load(UUID bookId) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT b.consumed,a.serialized_item FROM infinitygear_books b LEFT JOIN infinitygear_book_artifacts a ON a.book_id=b.book_id WHERE b.book_id=?")) {
            s.setString(1, bookId.toString());
            try (var r = s.executeQuery()) {
                if (!r.next() || r.getBoolean(1)) throw new IllegalArgumentException("Unknown or consumed book identity");
                return Optional.ofNullable(r.getBytes(2));
            }
        }
    }

    @Override public byte[] saveFirst(Receipt receipt, byte[] candidate) throws SQLException {
        if (candidate == null || candidate.length == 0 || candidate.length > 16_777_215)
            throw new IllegalArgumentException("Invalid serialized item size");
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE book_id=? FOR UPDATE")) {
                    s.setString(1, receipt.bookId().toString());
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !read(r).equals(receipt) || receipt.consumed())
                            throw new IllegalArgumentException("Unknown, modified or consumed book identity");
                    }
                }
                try (var s = c.prepareStatement("INSERT INTO infinitygear_book_artifacts(book_id,serialized_item) VALUES (?,?) ON DUPLICATE KEY UPDATE book_id=book_id")) {
                    s.setString(1, receipt.bookId().toString()); s.setBytes(2, candidate); s.executeUpdate();
                }
                byte[] saved;
                try (var s = c.prepareStatement("SELECT serialized_item FROM infinitygear_book_artifacts WHERE book_id=?")) {
                    s.setString(1, receipt.bookId().toString());
                    try (var r = s.executeQuery()) { if (!r.next()) throw new SQLException("Artifact missing"); saved = r.getBytes(1); }
                }
                c.commit(); return saved;
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    private static Receipt read(ResultSet r) throws SQLException {
        return new Receipt(UUID.fromString(r.getString("book_id")), new Issue(UUID.fromString(r.getString("operation_id")),
                UUID.fromString(r.getString("reward_id")), r.getString("enchantment_key"), r.getInt("enchantment_level"),
                r.getString("provenance_reference"), r.getBigDecimal("source_value")), r.getBoolean("consumed"));
    }

    @Override public Optional<Receipt> find(UUID operationId, UUID rewardId) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE operation_id=? AND reward_id=?")) {
            s.setString(1, operationId.toString()); s.setString(2, rewardId.toString());
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    /** Intentionally not registered as a live mutation capability until inventory recovery is integrated. */
    @Override public List<Receipt> transition(Request request, com.infinitygear.api.v1.ProvenancePolicy policy) throws Exception {
        String fingerprint = fingerprint(request);
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("INSERT INTO infinitygear_book_operations(operation_id,fingerprint) VALUES (?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id")) {
                    s.setString(1, request.operationId().toString()); s.setString(2, fingerprint); s.executeUpdate();
                }
                boolean completed;
                try (var s = c.prepareStatement("SELECT fingerprint,completed FROM infinitygear_book_operations WHERE operation_id=? FOR UPDATE")) {
                    s.setString(1, request.operationId().toString());
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !fingerprint.equals(r.getString(1))) throw new IllegalArgumentException("Operation payload mismatch");
                        completed = r.getBoolean(2);
                    }
                }
                if (!completed) {
                    var sources = new ArrayList<com.infinitygear.api.v1.ProvenancePolicy.Source>();
                    // Deterministic lock order avoids cross-operation parent-lock inversions.
                    for (UUID id : request.sourceBooks().stream().sorted().toList()) {
                        try (var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE book_id=? FOR UPDATE")) {
                            s.setString(1, id.toString());
                            try (var r = s.executeQuery()) {
                                if (!r.next()) throw new IllegalArgumentException("Unknown provenance source");
                                Receipt source = read(r);
                                if (source.consumed()) throw new IllegalArgumentException("Source already consumed");
                                sources.add(new com.infinitygear.api.v1.ProvenancePolicy.Source(id, true, source.issue().sourceValue()));
                            }
                        }
                    }
                    var decision = policy.decide(request.operation(), List.copyOf(sources), request.outputs().size());
                    if (!decision.allowed()) throw new IllegalStateException(decision.reason());
                    if (decision.outputValues().size() != request.outputs().size()) throw new IllegalArgumentException("Output count mismatch");
                    var total = sources.stream().map(com.infinitygear.api.v1.ProvenancePolicy.Source::value).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    var outputTotal = decision.outputValues().stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    if (outputTotal.compareTo(total) > 0) throw new IllegalArgumentException("Policy cannot mint provenance value");
                    for (int i = 0; i < request.outputs().size(); i++) {
                        var output = request.outputs().get(i);
                        var reward = UUID.nameUUIDFromBytes((request.operationId() + ":output:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var issue = new Issue(request.operationId(), reward, output.enchantmentKey(), output.level(), output.provenanceReference(), decision.outputValues().get(i));
                        try (var s = c.prepareStatement("INSERT INTO infinitygear_books(book_id,operation_id,reward_id,enchantment_key,enchantment_level,provenance_reference,source_value) VALUES (?,?,?,?,?,?,?)")) {
                            s.setString(1, UUID.randomUUID().toString()); s.setString(2, issue.operationId().toString()); s.setString(3, reward.toString());
                            s.setString(4, issue.enchantmentKey()); s.setInt(5, issue.level()); s.setString(6, issue.provenanceReference()); s.setBigDecimal(7, issue.sourceValue()); s.executeUpdate();
                        }
                    }
                    for (UUID id : request.sourceBooks()) {
                        try (var s = c.prepareStatement("UPDATE infinitygear_books SET consumed=TRUE WHERE book_id=? AND consumed=FALSE")) {
                            s.setString(1, id.toString()); if (s.executeUpdate() != 1) throw new SQLException("Source retirement conflict");
                        }
                        try (var s = c.prepareStatement("INSERT INTO infinitygear_book_lineage(operation_id,parent_id) VALUES (?,?)")) {
                            s.setString(1, request.operationId().toString()); s.setString(2, id.toString()); s.executeUpdate();
                        }
                    }
                    try (var s = c.prepareStatement("UPDATE infinitygear_book_operations SET completed=TRUE WHERE operation_id=?")) {
                        s.setString(1, request.operationId().toString()); s.executeUpdate();
                    }
                }
                var outputs = new ArrayList<Receipt>();
                try (var s = c.prepareStatement("SELECT * FROM infinitygear_books WHERE operation_id=? ORDER BY reward_id")) {
                    s.setString(1, request.operationId().toString());
                    try (var r = s.executeQuery()) { while (r.next()) outputs.add(read(r)); }
                }
                var byReward = new HashMap<UUID, Receipt>();
                outputs.forEach(receipt -> byReward.put(receipt.issue().rewardId(), receipt));
                var ordered = new ArrayList<Receipt>();
                for (int i = 0; i < request.outputs().size(); i++) {
                    UUID reward = UUID.nameUUIDFromBytes((request.operationId() + ":output:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var receipt = byReward.get(reward);
                    if (receipt == null) throw new SQLException("Missing lifecycle output");
                    ordered.add(receipt);
                }
                c.commit(); return List.copyOf(ordered);
            } catch (Exception failure) { c.rollback(); throw failure; }
        }
    }

    private static String fingerprint(Request request) throws Exception {
        // Length-prefix fields, avoiding delimiter ambiguities in external references.
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bytes)) {
            out.writeUTF(request.operation().name());
            out.writeInt(request.sourceBooks().size());
            for (var id : request.sourceBooks().stream().sorted().toList()) out.writeUTF(id.toString());
            out.writeInt(request.outputs().size());
            for (var output : request.outputs()) { out.writeUTF(output.enchantmentKey()); out.writeInt(output.level()); out.writeUTF(output.provenanceReference()); }
        }
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
