package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.integration.MiningCreditDeliveryProvider;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Blocking Archive v1 enrollment, ordering, and acknowledgment store. Never call on Bukkit's thread. */
public final class MariaMiningArchiveDelivery implements MiningCreditDeliveryProvider.Store {
    private static final String SUBSCRIBER = "archive-v1";
    private final DataSource source;

    public MariaMiningArchiveDelivery(DataSource source) { this.source = Objects.requireNonNull(source); }

    /** Additive migration 14. Repeated startup preserves the active subscription and all receipts. */
    public void migrate() throws SQLException {
        new MariaMiningJournal(source).migrate();
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_archive_subscription ("
                    + "subscription_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "active BOOLEAN NOT NULL,activated_at TIMESTAMP(6) NULL) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_mining_archive_subscription(subscription_id,active) "
                    + "VALUES ('archive-v1',FALSE)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_archive_deliveries ("
                    + "credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "digest_version SMALLINT UNSIGNED NOT NULL,payload_sha256 BINARY(32) NOT NULL,"
                    + "acknowledged_at TIMESTAMP(6) NULL,"
                    + "FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_credits(credit_id)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_archive_order ("
                    + "delivery_sequence BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,"
                    + "FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_archive_deliveries(credit_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (14)");
        }
        requireSchema();
    }

    /** Read-only bootstrap check after the reviewed migration is installed. */
    public void requireSchema() {
        try (var c = source.getConnection(); var s = c.prepareStatement(
                "SELECT 1 FROM infinitygear_schema_migrations WHERE version=14")) {
            try (var rows = s.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Archive delivery migration 14 is missing");
            }
            try (var check = c.prepareStatement("SELECT active FROM infinitygear_mining_archive_subscription WHERE subscription_id=?")) {
                check.setString(1, SUBSCRIBER);
                try (var rows = check.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Archive subscription row is missing");
                }
            }
            try (var check = c.prepareStatement("SELECT d.credit_id,o.delivery_sequence "
                    + "FROM infinitygear_mining_archive_deliveries d "
                    + "LEFT JOIN infinitygear_mining_archive_order o ON o.credit_id=d.credit_id LIMIT 0")) {
                check.executeQuery().close();
            }
        } catch (SQLException failure) { throw new IllegalStateException("Archive delivery schema unavailable", failure); }
    }

    /** Requires migration 14. Idempotent, with the same row lock used by receipt enrollment. */
    @Override public void activate() {
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                subscription(c, " FOR UPDATE");
                try (var s = c.prepareStatement("UPDATE infinitygear_mining_archive_subscription SET active=TRUE,activated_at=COALESCE(activated_at,CURRENT_TIMESTAMP(6)) WHERE subscription_id=?")) {
                    s.setString(1, SUBSCRIBER);
                    s.executeUpdate(); // a second activation may change zero rows
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Archive activation failed", failure); }
    }

    /** Called inside the new XP receipt transaction after its completed credit and receipt writes. */
    public void enroll(Connection c, MiningCredit credit) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(credit);
        if (c.getAutoCommit()) throw new IllegalStateException("Archive enrollment requires the XP transaction");
        if (!credit.legitimate() || !credit.successful()) throw new IllegalArgumentException("Unconfirmed credit");
        if (!subscription(c, " LOCK IN SHARE MODE")) return;
        try (var s = c.prepareStatement("INSERT INTO infinitygear_mining_archive_deliveries(credit_id,digest_version,payload_sha256) "
                + "SELECT m.credit_id,?,? FROM infinitygear_mining_credits m "
                + "JOIN infinitygear_mining_xp_receipts r ON r.credit_id=m.credit_id "
                + "WHERE m.credit_id=? AND m.state='COMPLETED'")) {
            s.setInt(1, MiningCreditDigest.VERSION);
            s.setBytes(2, MiningCreditDigest.sha256(credit));
            s.setString(3, credit.creditId().toString());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Only completed credits with atomic XP receipts can enroll");
        }
    }

    /** A short exclusive gate makes one committed enrollment batch visible before sequencing.
     * Gaps are permitted; sequence values are stable across restart and never reused. */
    public int sequencePending(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Sequence batch size must be 1..1000");
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                subscription(c, " FOR UPDATE");
                var ids = new ArrayList<String>();
                try (var s = c.prepareStatement("SELECT d.credit_id FROM infinitygear_mining_archive_deliveries d "
                        + "LEFT JOIN infinitygear_mining_archive_order o ON o.credit_id=d.credit_id "
                        + "WHERE o.credit_id IS NULL ORDER BY d.credit_id LIMIT ?")) {
                    s.setInt(1, limit);
                    try (var rows = s.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
                }
                try (var s = c.prepareStatement("INSERT INTO infinitygear_mining_archive_order(credit_id) VALUES (?)")) {
                    for (var id : ids) { s.setString(1, id); s.executeUpdate(); }
                }
                c.commit();
                return ids.size();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Archive sequencing failed", failure); }
    }

    /** Reads exactly the earliest unacknowledged sequenced delivery. A poison head blocks later credits. */
    @Override public List<MiningCredit> pendingNotifications(int limit) {
        if (limit != 1) throw new IllegalArgumentException("Archive delivery must process one ordered credit");
        // Sequencing is idempotent and also recovers enrollments committed before a crash.
        sequencePending(100);
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT m.*,r.credit_id AS receipt_credit_id,d.digest_version,d.payload_sha256 "
                + "FROM infinitygear_mining_archive_order o "
                + "JOIN infinitygear_mining_archive_deliveries d ON d.credit_id=o.credit_id "
                + "JOIN infinitygear_mining_credits m ON m.credit_id=d.credit_id "
                + "LEFT JOIN infinitygear_mining_xp_receipts r ON r.credit_id=m.credit_id "
                + "WHERE d.acknowledged_at IS NULL "
                + "ORDER BY o.delivery_sequence LIMIT 1")) {
            try (var rows = s.executeQuery()) {
                if (!rows.next()) return List.of();
                if (!"COMPLETED".equals(rows.getString("state")) || rows.getString("receipt_credit_id") == null)
                    throw new IllegalStateException("Archive delivery lacks a completed credit and XP receipt");
                var credit = MariaMiningJournal.readCredit(rows);
                verifyDigest(credit, rows.getInt("digest_version"), rows.getBytes("payload_sha256"));
                return List.of(credit);
            }
        } catch (SQLException failure) { throw new IllegalStateException("Archive delivery read failed", failure); }
    }

    @Override public void notificationDelivered(UUID creditId) {
        Objects.requireNonNull(creditId);
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                boolean alreadyAcknowledged;
                try (var s = c.prepareStatement("SELECT m.*,d.digest_version,d.payload_sha256,d.acknowledged_at,o.delivery_sequence "
                        + "FROM infinitygear_mining_archive_deliveries d "
                        + "JOIN infinitygear_mining_archive_order o ON o.credit_id=d.credit_id "
                        + "JOIN infinitygear_mining_credits m ON m.credit_id=d.credit_id "
                        + "JOIN infinitygear_mining_xp_receipts r ON r.credit_id=m.credit_id "
                        + "WHERE d.credit_id=? AND m.state='COMPLETED' FOR UPDATE")) {
                    s.setString(1, creditId.toString());
                    try (var rows = s.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("Archive credit lacks a committed receipt");
                        verifyDigest(MariaMiningJournal.readCredit(rows), rows.getInt("digest_version"), rows.getBytes("payload_sha256"));
                        alreadyAcknowledged = rows.getTimestamp("acknowledged_at") != null;
                    }
                }
                if (alreadyAcknowledged) { c.commit(); return; }
                // Never advance past an earlier undecided credit, even after a stale callback.
                try (var s = c.prepareStatement("SELECT o.credit_id FROM infinitygear_mining_archive_order o "
                        + "JOIN infinitygear_mining_archive_deliveries d ON d.credit_id=o.credit_id "
                        + "WHERE d.acknowledged_at IS NULL ORDER BY o.delivery_sequence LIMIT 1")) {
                    try (var rows = s.executeQuery()) {
                        if (rows.next() && !creditId.toString().equals(rows.getString(1)))
                            throw new IllegalStateException("Archive acknowledgment would skip an earlier credit");
                    }
                }
                try (var s = c.prepareStatement("UPDATE infinitygear_mining_archive_deliveries "
                        + "SET acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP(6)) WHERE credit_id=?")) {
                    s.setString(1, creditId.toString());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("Archive delivery disappeared");
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Archive acknowledgment failed", failure); }
    }

    private boolean subscription(Connection c, String lock) throws SQLException {
        try (var s = c.prepareStatement("SELECT active FROM infinitygear_mining_archive_subscription WHERE subscription_id=?" + lock)) {
            s.setString(1, SUBSCRIBER);
            try (var rows = s.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Archive subscription migration missing");
                return rows.getBoolean(1);
            }
        }
    }

    private static void verifyDigest(MiningCredit credit, int version, byte[] saved) {
        if (version != MiningCreditDigest.VERSION || !Arrays.equals(saved, MiningCreditDigest.sha256(credit)))
            throw new IllegalStateException("Archive credit payload digest mismatch: " + credit.creditId());
    }
}
