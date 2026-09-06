package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import com.infinitygear.mining.MiningXpPlan.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Blocking, opt-in database-authoritative XP participant. Not registered for legacy items.
 * Before activation every XP writer and item projection must honor these account revisions.
 * No inventory save, level-up event or physical-break confirmation is implied by a DB receipt. */
public final class MariaMiningXpLedger {
    public record Receipt(MiningCredit credit, MiningXpPlan plan, Account account) { }
    private final DataSource source;
    public MariaMiningXpLedger(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void migrate() throws SQLException {
        new MariaMiningJournal(source).migrate();
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_xp_accounts (pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, profile_id VARCHAR(256) NOT NULL, revision BIGINT NOT NULL, level_value INT NOT NULL, xp DOUBLE NOT NULL, blocks_mined BIGINT NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_mining_xp_receipts (credit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, revision BIGINT NOT NULL, plan MEDIUMBLOB NOT NULL, UNIQUE (pickaxe_id, revision), FOREIGN KEY (credit_id) REFERENCES infinitygear_mining_credits(credit_id), FOREIGN KEY (pickaxe_id) REFERENCES infinitygear_xp_accounts(pickaxe_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (6)");
        }
    }
    /** Explicit adoption only after custody and legacy-writer exclusion are established by caller.
     * Existing accounts return authoritative progress; this method never resets their XP. */
    public Account initialize(UUID id, String profile, Progress baseline) throws SQLException {
        Objects.requireNonNull(id); Objects.requireNonNull(baseline);
        if (profile == null || profile.isBlank() || profile.length() > 256) throw new IllegalArgumentException("Invalid profile");
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO infinitygear_xp_accounts VALUES (?,?,0,?,?,?) ON DUPLICATE KEY UPDATE pickaxe_id=VALUES(pickaxe_id)")) {
            s.setString(1, id.toString()); s.setString(2, profile); s.setInt(3, baseline.level());
            s.setDouble(4, baseline.xp()); s.setLong(5, baseline.blocksMined()); s.executeUpdate();
            var account = account(c, id, false).orElseThrow();
            if (!account.profileId().equals(profile)) throw new IllegalArgumentException("XP account profile mismatch");
            return account;
        }
    }
    public Optional<Account> findAccount(UUID id) throws SQLException {
        try (var c = source.getConnection()) { return account(c, Objects.requireNonNull(id), false); }
    }
    public Optional<Receipt> findReceipt(UUID creditId) throws SQLException {
        try (var c = source.getConnection()) { return receipt(c, Objects.requireNonNull(creditId)); }
    }
    /** Find the next absolute projection when an item reloads with an older revision. */
    public Optional<Receipt> findReceipt(UUID pickaxeId, long revision) throws SQLException {
        Objects.requireNonNull(pickaxeId);
        if (revision < 1) throw new IllegalArgumentException("Receipt revision must be positive");
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT credit_id FROM infinitygear_mining_xp_receipts WHERE pickaxe_id=? AND revision=?")) {
            s.setString(1, pickaxeId.toString()); s.setLong(2, revision);
            try (var row = s.executeQuery()) {
                return row.next() ? receipt(c, UUID.fromString(row.getString(1))) : Optional.empty();
            }
        }
    }
    /** XP, receipt and completed mining credit share one commit; a retry cannot add XP twice.
     * Existing legacy reservations have no XP evidence and are rejected, never adopted as unpaid. */
    public Receipt apply(MiningCredit credit, MiningXpPlan plan) throws SQLException {
        Objects.requireNonNull(credit); Objects.requireNonNull(plan);
        if (!credit.legitimate() || !credit.successful() || !credit.pickaxeId().equals(plan.pickaxeId())
                || !credit.profileId().equals(plan.profileId()) || Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
                .contains(credit.originalBlockData().split("\\[", 2)[0])) throw new IllegalArgumentException("Ineligible mining XP credit");
        Progress after = plan.after(); long nextRevision = Math.addExact(plan.expectedRevision(), 1);
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                var current = account(c, plan.pickaxeId(), true).orElseThrow(() -> new IllegalStateException("XP account has not been adopted"));
                var saved = receipt(c, credit.creditId());
                if (saved.isPresent()) {
                    if (!saved.get().credit().equals(credit) || !saved.get().plan().equals(plan))
                        throw new IllegalArgumentException("Mining XP retry payload mismatch");
                    c.commit(); return saved.get();
                }
                if (current.revision() != plan.expectedRevision() || !current.progress().equals(plan.before())
                        || !current.profileId().equals(plan.profileId())) throw new IllegalStateException("Stale XP account revision or progress");
                try (var s = c.prepareStatement("INSERT INTO infinitygear_mining_credits(instance_id,credit_id,player_id,pickaxe_id,profile_id,world_id,block_x,block_y,block_z,original_data,source_type,generation_ref,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'COMPLETED')")) {
                    s.setString(1, credit.instanceId().toString()); s.setString(2, credit.creditId().toString());
                    s.setString(3, credit.playerId().toString()); s.setString(4, credit.pickaxeId().toString());
                    s.setString(5, credit.profileId()); s.setString(6, credit.worldId().toString());
                    s.setInt(7, credit.x()); s.setInt(8, credit.y()); s.setInt(9, credit.z());
                    s.setString(10, credit.originalBlockData()); s.setString(11, credit.source().name()); s.setString(12, credit.generation());
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO infinitygear_mining_xp_receipts(credit_id,pickaxe_id,revision,plan) VALUES (?,?,?,?)")) {
                    s.setString(1, credit.creditId().toString()); s.setString(2, plan.pickaxeId().toString());
                    s.setLong(3, nextRevision); s.setBytes(4, plan.encode()); s.executeUpdate();
                }
                try (var s = c.prepareStatement("UPDATE infinitygear_xp_accounts SET revision=?,level_value=?,xp=?,blocks_mined=? WHERE pickaxe_id=?")) {
                    s.setLong(1, nextRevision); s.setInt(2, after.level()); s.setDouble(3, after.xp());
                    s.setLong(4, after.blocksMined()); s.setString(5, plan.pickaxeId().toString());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("XP account disappeared");
                }
                c.commit(); return new Receipt(credit, plan, new Account(plan.pickaxeId(), plan.profileId(), nextRevision, after));
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
    private Optional<Account> account(Connection c, UUID id, boolean lock) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM infinitygear_xp_accounts WHERE pickaxe_id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, id.toString());
            try (var row = s.executeQuery()) {
                return row.next() ? Optional.of(new Account(id, row.getString("profile_id"), row.getLong("revision"),
                        new Progress(row.getInt("level_value"), row.getDouble("xp"), row.getLong("blocks_mined")))) : Optional.empty();
            }
        }
    }
    private Optional<Receipt> receipt(Connection c, UUID id) throws SQLException {
        try (var s = c.prepareStatement("SELECT m.*,r.plan FROM infinitygear_mining_xp_receipts r JOIN infinitygear_mining_credits m ON m.credit_id=r.credit_id WHERE r.credit_id=?")) {
            s.setString(1, id.toString());
            try (var row = s.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var plan = MiningXpPlan.decode(row.getBytes("plan"));
                return Optional.of(new Receipt(MariaMiningJournal.readCredit(row), plan,
                        new Account(plan.pickaxeId(), plan.profileId(), Math.addExact(plan.expectedRevision(), 1), plan.after())));
            }
        }
    }
}
