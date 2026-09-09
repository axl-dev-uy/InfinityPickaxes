package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Blocking durable receiving boundary. Call only on the integration database executor. */
public final class MariaMiningCreditInbox {
    public record Acceptance(MiningCredit credit, boolean inserted) { }

    private final DataSource source;

    public MariaMiningCreditInbox(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void migrate() throws SQLException {
        new MariaMiningXpLedger(source).migrate();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_consumer_inbox ("
                    + "credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "player_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "profile_id VARCHAR(256) NOT NULL,world_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "block_x INT NOT NULL,block_y INT NOT NULL,block_z INT NOT NULL,original_data TEXT NOT NULL,"
                    + "source_type VARCHAR(32) NOT NULL,generation_ref VARCHAR(512) NOT NULL,"
                    + "accepted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            statement.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (9)");
        }
    }

    /** Returns only after commit. An identical replay is accepted; a reused ID with changed facts is rejected. */
    public Acceptance accept(MiningCredit credit) throws SQLException {
        Objects.requireNonNull(credit);
        if (!credit.legitimate() || !credit.successful())
            throw new IllegalArgumentException("Only receipt-backed successful credits may enter the consumer inbox");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int inserted;
                try (var statement = connection.prepareStatement("INSERT IGNORE INTO infinitygear_mining_consumer_inbox(credit_id,instance_id,player_id,pickaxe_id,profile_id,world_id,block_x,block_y,block_z,original_data,source_type,generation_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    int i = 1;
                    statement.setString(i++, credit.creditId().toString());
                    statement.setString(i++, credit.instanceId().toString());
                    statement.setString(i++, credit.playerId().toString());
                    statement.setString(i++, credit.pickaxeId().toString());
                    statement.setString(i++, credit.profileId());
                    statement.setString(i++, credit.worldId().toString());
                    statement.setInt(i++, credit.x()); statement.setInt(i++, credit.y()); statement.setInt(i++, credit.z());
                    statement.setString(i++, credit.originalBlockData()); statement.setString(i++, credit.source().name());
                    statement.setString(i, credit.generation());
                    inserted = statement.executeUpdate();
                }
                MiningCredit saved;
                try (var statement = connection.prepareStatement("SELECT * FROM infinitygear_mining_consumer_inbox WHERE credit_id=? FOR UPDATE")) {
                    statement.setString(1, credit.creditId().toString());
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalStateException("Consumer inbox insert disappeared");
                        saved = MariaMiningJournal.readCredit(row);
                    }
                }
                if (!saved.equals(credit)) throw new IllegalArgumentException("Consumer credit replay payload mismatch");
                connection.commit();
                return new Acceptance(saved, inserted == 1);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
