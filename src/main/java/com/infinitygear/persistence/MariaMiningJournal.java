package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningCoordinator;
import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

/** Blocking durable journal. Wire through an async participant executor, never directly into event dispatch. */
public final class MariaMiningJournal implements MiningCoordinator.Journal {
    private final DataSource source;
    public MariaMiningJournal(DataSource source) { this.source = source; }
    public void migrate() throws SQLException {
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_credits (instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE, player_id CHAR(36) NOT NULL, pickaxe_id CHAR(36) NOT NULL, profile_id VARCHAR(256) NOT NULL, world_id CHAR(36) NOT NULL, block_x INT NOT NULL, block_y INT NOT NULL, block_z INT NOT NULL, original_data TEXT NOT NULL, source_type VARCHAR(32) NOT NULL, generation_ref VARCHAR(512) NOT NULL, state VARCHAR(32) NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (3)");
        }
    }
    @Override public boolean reserve(MiningCredit credit) {
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
    private void transition(UUID id, String state) {
        try (var c = source.getConnection(); var s = c.prepareStatement("UPDATE infinitygear_mining_credits SET state=? WHERE instance_id=? AND state='RESERVED'")) {
            s.setString(1, state); s.setString(2, id.toString());
            if (s.executeUpdate() != 1) throw new IllegalStateException("Unexpected mining journal state");
        } catch (SQLException failure) { throw new IllegalStateException("Mining journal transition failed", failure); }
    }
}
