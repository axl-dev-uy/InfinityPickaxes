package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningCoordinator;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Blocking durable journal. Wire through an async participant executor, never directly into event dispatch. */
public final class MariaMiningJournal implements MiningCoordinator.Journal, com.infinitygear.mining.MiningNotificationDispatcher.Outbox {
    public enum State { RESERVED, RECOVERY_REQUIRED, COMPLETED }
    /** Journal state is not proof that Bukkit XP is durably saved. */
    public record Entry(MiningCredit credit, State state) {
        public Entry { Objects.requireNonNull(credit); Objects.requireNonNull(state); }
    }
    private final DataSource source;
    public MariaMiningJournal(DataSource source) { this.source = source; }
    public void migrate() throws SQLException {
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_credits (instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE, player_id CHAR(36) NOT NULL, pickaxe_id CHAR(36) NOT NULL, profile_id VARCHAR(256) NOT NULL, world_id CHAR(36) NOT NULL, block_x INT NOT NULL, block_y INT NOT NULL, block_z INT NOT NULL, original_data TEXT NOT NULL, source_type VARCHAR(32) NOT NULL, generation_ref VARCHAR(512) NOT NULL, state VARCHAR(32) NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (3)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_notifications (credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, delivered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_credits(credit_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (5)");
        }
    }
    @Override public boolean reserve(MiningCredit credit) {
        if (!credit.legitimate() || !credit.successful())
            throw new IllegalArgumentException("Only confirmed legitimate credits can be reserved");
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO infinitygear_mining_credits(instance_id,credit_id,player_id,pickaxe_id,profile_id,world_id,block_x,block_y,block_z,original_data,source_type,generation_ref,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED')")) {
            s.setString(1, credit.instanceId().toString()); s.setString(2, credit.creditId().toString());
            s.setString(3, credit.playerId().toString()); s.setString(4, credit.pickaxeId().toString());
            s.setString(5, credit.profileId()); s.setString(6, credit.worldId().toString());
            s.setInt(7, credit.x()); s.setInt(8, credit.y()); s.setInt(9, credit.z());
            s.setString(10, credit.originalBlockData()); s.setString(11, credit.source().name()); s.setString(12, credit.generation());
            s.executeUpdate(); return true;
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1062) return false;
            throw new IllegalStateException("Mining journal reservation failed", failure);
        }
    }
    @Override public void complete(UUID id) { transition(id, "COMPLETED"); }
    @Override public void needsRecovery(UUID id) { transition(id, "RECOVERY_REQUIRED"); }
    /** Read-only inspection by physical instance, including completed records. Blocking JDBC. */
    public Optional<Entry> find(UUID instanceId) {
        Objects.requireNonNull(instanceId);
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT * FROM infinitygear_mining_credits WHERE instance_id=?")) {
            s.setString(1, instanceId.toString());
            try (var rows = s.executeQuery()) {
                return rows.next() ? Optional.of(new Entry(readCredit(rows), State.valueOf(rows.getString("state")))) : Optional.empty();
            }
        } catch (SQLException failure) { throw new IllegalStateException("Mining journal inspection failed", failure); }
    }
    /** Enumerates ambiguous and interrupted records, never claiming or resolving them.
     * Null cursor starts a pass; otherwise pass the last entry's instanceId, not creditId.
     * Ordering is canonical UUID text under ascii_bin, not Java UUID.compareTo or creation time.
     * This is a live scan, not a snapshot: newly inserted/changed rows before the cursor are found
     * on the next full pass. A RESERVED row may still belong to a running XP operation.
     * Call off-thread. Neither age nor presence here authorizes another XP write. */
    public List<Entry> pendingRecovery(UUID afterInstanceId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        String sql = "SELECT * FROM infinitygear_mining_credits WHERE state IN ('RESERVED','RECOVERY_REQUIRED')"
                + (afterInstanceId == null ? "" : " AND instance_id>?") + " ORDER BY instance_id LIMIT ?";
        try (var c = source.getConnection(); var s = c.prepareStatement(sql)) {
            int parameter = 1;
            if (afterInstanceId != null) s.setString(parameter++, afterInstanceId.toString());
            s.setInt(parameter, limit);
            try (var rows = s.executeQuery()) {
                var entries = new ArrayList<Entry>();
                while (rows.next()) entries.add(new Entry(readCredit(rows), State.valueOf(rows.getString("state"))));
                return List.copyOf(entries);
            }
        } catch (SQLException failure) { throw new IllegalStateException("Mining recovery inspection failed", failure); }
    }
    /** Completed journal rows are the outbox: completion and publication eligibility share one commit.
     * Reads do not claim delivery. Concurrent workers/restarts may replay; consumers deduplicate creditId.
     * RESERVED and RECOVERY_REQUIRED rows must be reconciled with XP before becoming publishable.
     * Blocking JDBC: a future dispatcher must read/ack off-thread and dispatch Bukkit events on-thread. */
    public List<MiningCredit> pendingNotifications(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT m.* FROM infinitygear_mining_credits m LEFT JOIN infinitygear_mining_notifications n ON n.credit_id=m.credit_id WHERE m.state='COMPLETED' AND n.credit_id IS NULL ORDER BY m.credit_id LIMIT ?")) {
            s.setInt(1, limit);
            try (var rows = s.executeQuery()) {
                var result = new ArrayList<MiningCredit>();
                while (rows.next()) result.add(readCredit(rows));
                return List.copyOf(result);
            }
        } catch (SQLException failure) { throw new IllegalStateException("Mining notification read failed", failure); }
    }
    /** Idempotent acknowledgement, only for a completed credit. Never grants permission to reapply XP. */
    @Override public void notificationDelivered(UUID creditId) {
        Objects.requireNonNull(creditId);
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO infinitygear_mining_notifications(credit_id) SELECT credit_id FROM infinitygear_mining_credits WHERE credit_id=? AND state='COMPLETED' ON DUPLICATE KEY UPDATE credit_id=VALUES(credit_id)")) {
            s.setString(1, creditId.toString());
            s.executeUpdate();
            try (var check = c.prepareStatement("SELECT credit_id FROM infinitygear_mining_notifications WHERE credit_id=?")) {
                check.setString(1, creditId.toString());
                try (var rows = check.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Only completed mining credits can be acknowledged");
                }
            }
        } catch (SQLException failure) { throw new IllegalStateException("Mining notification acknowledgement failed", failure); }
    }
    static MiningCredit readCredit(ResultSet rows) throws SQLException {
        return new MiningCredit(UUID.fromString(rows.getString("credit_id")),
                UUID.fromString(rows.getString("instance_id")), UUID.fromString(rows.getString("player_id")),
                UUID.fromString(rows.getString("pickaxe_id")), rows.getString("profile_id"),
                UUID.fromString(rows.getString("world_id")), rows.getInt("block_x"), rows.getInt("block_y"),
                rows.getInt("block_z"), rows.getString("original_data"),
                MiningCredit.Source.valueOf(rows.getString("source_type")), rows.getString("generation_ref"), true, true);
    }
    private void transition(UUID id, String state) {
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE infinitygear_mining_credits SET state=? WHERE instance_id=? AND state='RESERVED'")) {
            s.setString(1, state); s.setString(2, id.toString());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Unexpected mining journal state");
        } catch (SQLException failure) { throw new IllegalStateException("Mining journal transition failed", failure); }
    }
}
