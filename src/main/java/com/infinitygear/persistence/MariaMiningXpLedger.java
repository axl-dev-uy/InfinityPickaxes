package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import com.infinitygear.mining.MiningXpPlan.*;
import com.infinitygear.mining.XpProjectionReceipt;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Blocking, opt-in database-authoritative XP participant. Not registered for legacy items.
 * Before activation every XP writer and item projection must honor these account revisions.
 * No inventory save, level-up event or physical-break confirmation is implied by a DB receipt. */
public final class MariaMiningXpLedger {
    public record Receipt(MiningCredit credit, MiningXpPlan plan, Account account) { }
    public enum AdministrativeAction { ADD_XP, SET_LEVEL }
    public record Adoption(UUID adoptionId, UUID ownerId, Account account, String actor) { }
    public record AdministrativeReceipt(UUID operationId, UUID actorId, String actor,
                                        AdministrativeAction action, double requestedValue,
                                        Progress before, Account account) {
        public XpProjectionReceipt projection() {
            return new XpProjectionReceipt(operationId,
                    action == AdministrativeAction.ADD_XP ? XpProjectionReceipt.Kind.ADMIN_ADD_XP
                            : XpProjectionReceipt.Kind.ADMIN_SET_LEVEL,
                    account.pickaxeId(), account.profileId(), account.revision() - 1, before, account);
        }
    }
    public record Reconciliation(UUID pickaxeId, String status, String detail, String location,
                                 java.time.Instant firstSeen, java.time.Instant updatedAt) { }
    private final DataSource source;
    public MariaMiningXpLedger(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void migrate() throws SQLException {
        new MariaMiningJournal(source).migrate();
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_xp_adoptions (pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, adoption_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE, owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, actor VARCHAR(256) NOT NULL, adopted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY (pickaxe_id) REFERENCES infinitygear_xp_accounts(pickaxe_id)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_xp_admin_receipts (operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, revision BIGINT NOT NULL, actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, actor VARCHAR(256) NOT NULL, action_type VARCHAR(32) NOT NULL, requested_value DOUBLE NOT NULL, before_level INT NOT NULL, before_xp DOUBLE NOT NULL, before_blocks BIGINT NOT NULL, after_level INT NOT NULL, after_xp DOUBLE NOT NULL, after_blocks BIGINT NOT NULL, UNIQUE (pickaxe_id,revision), FOREIGN KEY (pickaxe_id) REFERENCES infinitygear_xp_accounts(pickaxe_id)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_xp_reconciliation (pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, status VARCHAR(64) NOT NULL, detail TEXT NOT NULL, location_text VARCHAR(512) NOT NULL, first_seen TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_xp_presentations (pickaxe_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, revision BIGINT NOT NULL, effect_type VARCHAR(64) NOT NULL, presented_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY (pickaxe_id,revision,effect_type), FOREIGN KEY (pickaxe_id) REFERENCES infinitygear_xp_accounts(pickaxe_id)) ENGINE=InnoDB");
            s.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (8)");
        }
    }

    /** Creates authority only for a caller-fenced physical item. A pre-existing unadopted account is a conflict. */
    public Adoption adopt(UUID adoptionId, UUID ownerId, UUID id, String profile,
                          Progress baseline, String actor) throws SQLException {
        Objects.requireNonNull(adoptionId); Objects.requireNonNull(ownerId); Objects.requireNonNull(id);
        Objects.requireNonNull(baseline);
        if (profile == null || profile.isBlank() || profile.length() > 256
                || actor == null || actor.isBlank() || actor.length() > 256)
            throw new IllegalArgumentException("Invalid XP adoption");
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (var s = c.prepareStatement("INSERT IGNORE INTO infinitygear_xp_accounts VALUES (?,?,0,?,?,?)")) {
                    s.setString(1, id.toString()); s.setString(2, profile); s.setInt(3, baseline.level());
                    s.setDouble(4, baseline.xp()); s.setLong(5, baseline.blocksMined()); inserted = s.executeUpdate();
                }
                var current = account(c, id, true).orElseThrow();
                var existing = adoption(c, id, true);
                if (existing.isEmpty()) {
                    if (inserted != 1) throw new IllegalStateException("XP account exists without this explicit adoption");
                    try (var s = c.prepareStatement("INSERT INTO infinitygear_xp_adoptions(pickaxe_id,adoption_id,owner_id,actor) VALUES (?,?,?,?)")) {
                        s.setString(1, id.toString()); s.setString(2, adoptionId.toString());
                        s.setString(3, ownerId.toString()); s.setString(4, actor); s.executeUpdate();
                    }
                    existing = Optional.of(new Adoption(adoptionId, ownerId, current, actor));
                }
                Adoption result = existing.orElseThrow();
                if (!result.adoptionId().equals(adoptionId) || !result.ownerId().equals(ownerId)
                        || !result.actor().equals(actor) || !result.account().profileId().equals(profile)
                        || !result.account().progress().equals(baseline))
                    throw new IllegalArgumentException("XP adoption replay or baseline mismatch");
                c.commit(); return result;
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    public Optional<Adoption> findAdoption(UUID pickaxeId) throws SQLException {
        try (var c = source.getConnection()) { return adoption(c, Objects.requireNonNull(pickaxeId), false); }
    }
    public Optional<AdministrativeReceipt> findAdministrativeReceipt(UUID operationId) throws SQLException {
        try (var c = source.getConnection()) {
            return administrativeReceipt(c, Objects.requireNonNull(operationId));
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

    /** Finds exactly one committed absolute transition. Two sources at one revision fail closed. */
    public Optional<XpProjectionReceipt> findProjection(UUID pickaxeId, long revision) throws SQLException {
        Objects.requireNonNull(pickaxeId);
        if (revision < 1) throw new IllegalArgumentException("Receipt revision must be positive");
        try (var c = source.getConnection()) {
            XpProjectionReceipt found = null;
            var mining = findReceipt(pickaxeId, revision);
            if (mining.isPresent()) {
                Receipt receipt = mining.get();
                found = new XpProjectionReceipt(receipt.credit().creditId(), XpProjectionReceipt.Kind.MINING,
                        pickaxeId, receipt.plan().profileId(), receipt.plan().expectedRevision(),
                        receipt.plan().before(), receipt.account());
            }
            try (var s = c.prepareStatement("SELECT a.profile_id,r.* FROM infinitygear_xp_admin_receipts r JOIN infinitygear_xp_accounts a ON a.pickaxe_id=r.pickaxe_id WHERE r.pickaxe_id=? AND r.revision=?")) {
                s.setString(1, pickaxeId.toString()); s.setLong(2, revision);
                try (var row = s.executeQuery()) {
                    if (row.next()) {
                        if (found != null) throw new IllegalStateException("Conflicting XP receipts at revision " + revision);
                        var before = new Progress(row.getInt("before_level"), row.getDouble("before_xp"), row.getLong("before_blocks"));
                        var after = new Progress(row.getInt("after_level"), row.getDouble("after_xp"), row.getLong("after_blocks"));
                        var action = AdministrativeAction.valueOf(row.getString("action_type"));
                        found = new XpProjectionReceipt(UUID.fromString(row.getString("operation_id")),
                                action == AdministrativeAction.ADD_XP ? XpProjectionReceipt.Kind.ADMIN_ADD_XP : XpProjectionReceipt.Kind.ADMIN_SET_LEVEL,
                                pickaxeId, row.getString("profile_id"), revision - 1, before,
                                new Account(pickaxeId, row.getString("profile_id"), revision, after));
                    }
                }
            }
            return Optional.ofNullable(found);
        }
    }

    /** Atomic, idempotent administrative progression. It never increments mined count. */
    public AdministrativeReceipt administer(UUID operationId, UUID actorId, String actor,
                                             UUID pickaxeId, String profileId, long expectedRevision,
                                             Progress before, AdministrativeAction action, double value,
                                             List<Double> requiredXp) throws SQLException {
        Objects.requireNonNull(operationId); Objects.requireNonNull(actorId); Objects.requireNonNull(pickaxeId);
        Objects.requireNonNull(before); Objects.requireNonNull(action); requiredXp = List.copyOf(requiredXp);
        if (actor == null || actor.isBlank() || actor.length() > 256 || profileId == null || profileId.isBlank()
                || expectedRevision < 0 || !Double.isFinite(value) || value < 0)
            throw new IllegalArgumentException("Invalid administrative XP operation");
        Progress after = switch (action) {
            case ADD_XP -> addAdministrativeXp(before, value, requiredXp);
            case SET_LEVEL -> {
                if (value != Math.rint(value) || value > Integer.MAX_VALUE || value > requiredXp.size())
                    throw new IllegalArgumentException("Invalid administrative level");
                yield new Progress((int) value, 0, before.blocksMined());
            }
        };
        long revision = Math.addExact(expectedRevision, 1);
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                var saved = administrativeReceipt(c, operationId);
                if (saved.isPresent()) {
                    AdministrativeReceipt receipt = saved.get();
                    if (!sameAdminRequest(receipt, actorId, actor, pickaxeId, profileId, expectedRevision, before, after, action, value))
                        throw new IllegalArgumentException("Administrative XP replay payload mismatch");
                    c.commit(); return receipt;
                }
                var current = account(c, pickaxeId, true).orElseThrow(() -> new IllegalStateException("XP account has not been adopted"));
                if (adoption(c, pickaxeId, false).isEmpty())
                    throw new IllegalStateException("XP account lacks explicit physical-item adoption");
                if (current.revision() != expectedRevision || !current.profileId().equals(profileId)
                        || !current.progress().equals(before)) throw new IllegalStateException("Stale XP account revision or progress");
                try (var s = c.prepareStatement("INSERT INTO infinitygear_xp_admin_receipts VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    int i = 1; s.setString(i++, operationId.toString()); s.setString(i++, pickaxeId.toString());
                    s.setLong(i++, revision); s.setString(i++, actorId.toString()); s.setString(i++, actor);
                    s.setString(i++, action.name()); s.setDouble(i++, value); s.setInt(i++, before.level());
                    s.setDouble(i++, before.xp()); s.setLong(i++, before.blocksMined()); s.setInt(i++, after.level());
                    s.setDouble(i++, after.xp()); s.setLong(i, after.blocksMined()); s.executeUpdate();
                }
                try (var s = c.prepareStatement("UPDATE infinitygear_xp_accounts SET revision=?,level_value=?,xp=?,blocks_mined=? WHERE pickaxe_id=?")) {
                    s.setLong(1, revision); s.setInt(2, after.level()); s.setDouble(3, after.xp());
                    s.setLong(4, after.blocksMined()); s.setString(5, pickaxeId.toString());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("XP account disappeared");
                }
                c.commit(); return new AdministrativeReceipt(operationId, actorId, actor, action, value, before,
                        new Account(pickaxeId, profileId, revision, after));

            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    public void recordReconciliation(UUID pickaxeId, String status, String detail, String location) throws SQLException {
        Objects.requireNonNull(pickaxeId);
        if (status == null || status.isBlank() || status.length() > 64 || detail == null || location == null || location.length() > 512)
            throw new IllegalArgumentException("Invalid XP reconciliation issue");
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT INTO infinitygear_xp_reconciliation(pickaxe_id,status,detail,location_text) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),detail=VALUES(detail),location_text=VALUES(location_text)")) {
            s.setString(1, pickaxeId.toString()); s.setString(2, status); s.setString(3, detail); s.setString(4, location); s.executeUpdate();
        }
    }
    public void clearReconciliation(UUID pickaxeId) throws SQLException {
        try (var c = source.getConnection(); var s = c.prepareStatement("DELETE FROM infinitygear_xp_reconciliation WHERE pickaxe_id=?")) {
            s.setString(1, Objects.requireNonNull(pickaxeId).toString()); s.executeUpdate();
        }
    }
    public List<Reconciliation> reconciliations(int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid reconciliation limit");
        try (var c = source.getConnection(); var s = c.prepareStatement("SELECT * FROM infinitygear_xp_reconciliation ORDER BY updated_at DESC LIMIT ?")) {
            s.setInt(1, limit); try (var rows = s.executeQuery()) { var result = new ArrayList<Reconciliation>();
                while (rows.next()) result.add(new Reconciliation(UUID.fromString(rows.getString("pickaxe_id")), rows.getString("status"),
                        rows.getString("detail"), rows.getString("location_text"), rows.getTimestamp("first_seen").toInstant(), rows.getTimestamp("updated_at").toInstant()));
                return List.copyOf(result); }
        }
    }
    public boolean claimPresentation(UUID pickaxeId, long revision, String effectType) throws SQLException {
        Objects.requireNonNull(pickaxeId);
        if (revision < 1 || effectType == null || effectType.isBlank() || effectType.length() > 64)
            throw new IllegalArgumentException("Invalid XP presentation claim");
        try (var c = source.getConnection(); var s = c.prepareStatement("INSERT IGNORE INTO infinitygear_xp_presentations(pickaxe_id,revision,effect_type) VALUES (?,?,?)")) {
            s.setString(1, pickaxeId.toString()); s.setLong(2, revision); s.setString(3, effectType);
            return s.executeUpdate() == 1;
        }
    }
    /** XP, receipt and completed mining credit share one commit; a retry cannot add XP twice.
     * Existing legacy reservations have no XP evidence and are rejected, never adopted as unpaid. */
    public Receipt apply(MiningCredit credit, MiningXpPlan plan) throws SQLException {
        return apply(credit, plan, evidence(credit));
    }
    public Receipt apply(MiningCredit credit, MiningXpPlan plan,
                         com.infinitygear.api.v1.MiningIncidentService.Evidence evidence) throws SQLException {
        Objects.requireNonNull(credit); Objects.requireNonNull(plan);
        if (!evidence.operationId().equals(credit.creditId()) || !evidence.playerId().equals(credit.playerId())
                || !Objects.equals(evidence.itemId(), credit.pickaxeId()) || !evidence.worldId().equals(credit.worldId())
                || evidence.x() != credit.x() || evidence.y() != credit.y() || evidence.z() != credit.z()
                || !evidence.originalData().equals(credit.originalBlockData())) throw new IllegalArgumentException("Evidence attribution mismatch");
        if (!credit.legitimate() || !credit.successful() || !credit.pickaxeId().equals(plan.pickaxeId())
                || !credit.profileId().equals(plan.profileId()) || Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
                .contains(credit.originalBlockData().split("\\[", 2)[0])) throw new IllegalArgumentException("Ineligible mining XP credit");
        Progress after = plan.after(); long nextRevision = Math.addExact(plan.expectedRevision(), 1);
        try (var c = source.getConnection()) {
            // See the separately committed submission marker; account FOR UPDATE still serializes XP.
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
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
                // A separate autocommit persists the one-shot marker while this transaction holds
                // the account lock. Concurrent identical calls wait for this transaction's outcome.
                // A crash/rollback after this marker NEVER permits a new award on a subsequent call.
                if (!new MariaMiningJournal(source).recordAttempt(evidence, credit.instanceId(), MariaMiningJournal.AttemptState.CONFIRMED))
                    throw new IllegalStateException("Mining operation already submitted without a committed receipt; voided, no replay");
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
                if (!MariaMiningJournal.finishAttempt(c, credit.creditId(), MariaMiningJournal.AttemptState.COMMITTED, "XP_RECEIPT_COMMITTED"))
                    throw new IllegalStateException("Attempt was voided before commit");
                c.commit(); return new Receipt(credit, plan, new Account(plan.pickaxeId(), plan.profileId(), nextRevision, after));
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            // Forensics only, after releasing the transaction/connection. A response loss may
            // already have committed; never overwrite its receipt and never submit XP again.
            try { recoverOperation(credit, plan); }
            catch (SQLException | RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
            throw failure;
        }
    }
    /** Receipt-only recovery. The account lock waits for any in-flight XP transaction to finish;
     * a missing receipt is a voided operation, never an instruction to call apply again. */
    public Optional<Receipt> recoverOperation(MiningCredit credit, MiningXpPlan plan) throws SQLException {
        if (!credit.pickaxeId().equals(plan.pickaxeId()) || !credit.profileId().equals(plan.profileId()))
            throw new IllegalArgumentException("Recovery account mismatch");
        try (var c = source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                account(c, plan.pickaxeId(), true);
                var saved = receipt(c, credit.creditId());
                if (saved.isPresent() && (!saved.get().credit().equals(credit) || !saved.get().plan().equals(plan)))
                    throw new IllegalArgumentException("Mining recovery payload mismatch");
                MariaMiningJournal.finishAttempt(c, credit.creditId(), saved.isPresent()
                        ? MariaMiningJournal.AttemptState.COMMITTED : MariaMiningJournal.AttemptState.COMMIT_FAILED,
                        saved.isPresent() ? "XP_RECEIPT_COMMITTED" : "NO_COMMITTED_RECEIPT_NO_REPLAY");
                c.commit(); return saved;
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }
    /** Legacy internal callers have incomplete forensic context. Live adapters must pass captured evidence. */
    private static com.infinitygear.api.v1.MiningIncidentService.Evidence evidence(MiningCredit credit) {
        UUID generation;
        try { generation = UUID.fromString(credit.generation()); } catch (IllegalArgumentException unknown) { generation = null; }
        return new com.infinitygear.api.v1.MiningIncidentService.Evidence(credit.creditId(), credit.playerId(), credit.pickaxeId(),
                "unavailable", generation, credit.worldId(), credit.x(), credit.y(), credit.z(), credit.originalBlockData(),
                credit.legitimate() ? "PROVIDER_LEGITIMATE" : "UNKNOWN", "trusted internal completion; generation=" + credit.generation(),
                "XP_SUBMISSION", "CONFIRMED_SUBMISSION", java.time.Instant.now(), Map.of("context", "internal-unavailable"), "unavailable");
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
    private Optional<Adoption> adoption(Connection c, UUID id, boolean lock) throws SQLException {
        try (var s = c.prepareStatement("SELECT d.*,a.profile_id,a.revision,a.level_value,a.xp,a.blocks_mined FROM infinitygear_xp_adoptions d JOIN infinitygear_xp_accounts a ON a.pickaxe_id=d.pickaxe_id WHERE d.pickaxe_id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, id.toString());
            try (var row = s.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var account = new Account(id, row.getString("profile_id"), row.getLong("revision"),
                        new Progress(row.getInt("level_value"), row.getDouble("xp"), row.getLong("blocks_mined")));
                return Optional.of(new Adoption(UUID.fromString(row.getString("adoption_id")),
                        UUID.fromString(row.getString("owner_id")), account, row.getString("actor")));
            }
        }
    }
    private Optional<AdministrativeReceipt> administrativeReceipt(Connection c, UUID operationId) throws SQLException {
        try (var s = c.prepareStatement("SELECT a.profile_id,r.* FROM infinitygear_xp_admin_receipts r JOIN infinitygear_xp_accounts a ON a.pickaxe_id=r.pickaxe_id WHERE r.operation_id=?")) {
            s.setString(1, operationId.toString());
            try (var row = s.executeQuery()) {
                if (!row.next()) return Optional.empty();
                UUID id = UUID.fromString(row.getString("pickaxe_id"));
                var before = new Progress(row.getInt("before_level"), row.getDouble("before_xp"), row.getLong("before_blocks"));
                var after = new Progress(row.getInt("after_level"), row.getDouble("after_xp"), row.getLong("after_blocks"));
                return Optional.of(new AdministrativeReceipt(operationId, UUID.fromString(row.getString("actor_id")),
                        row.getString("actor"), AdministrativeAction.valueOf(row.getString("action_type")),
                        row.getDouble("requested_value"), before,
                        new Account(id, row.getString("profile_id"), row.getLong("revision"), after)));
            }
        }
    }
    private static boolean sameAdminRequest(AdministrativeReceipt receipt, UUID actorId, String actor,
                                            UUID pickaxeId, String profileId, long expectedRevision,
                                            Progress before, Progress after, AdministrativeAction action, double value) {
        return receipt.actorId().equals(actorId) && receipt.actor().equals(actor)
                && receipt.account().pickaxeId().equals(pickaxeId) && receipt.account().profileId().equals(profileId)
                && receipt.account().revision() == expectedRevision + 1 && receipt.before().equals(before)
                && receipt.account().progress().equals(after) && receipt.action() == action
                && Double.compare(receipt.requestedValue(), value) == 0;
    }
    private static Progress addAdministrativeXp(Progress before, double amount, List<Double> requiredXp) {
        if (requiredXp.size() > 10000 || before.level() > requiredXp.size())
            throw new IllegalArgumentException("Invalid captured level requirements");
        for (double requirement : requiredXp) if (!Double.isFinite(requirement) || requirement <= 0)
            throw new IllegalArgumentException("Invalid captured level requirement");
        int level = before.level(); double xp = before.xp();
        if (level < requiredXp.size()) {
            xp += amount;
            if (!Double.isFinite(xp)) throw new IllegalArgumentException("XP overflow");
            while (level < requiredXp.size() && xp >= requiredXp.get(level)) xp -= requiredXp.get(level++);
        }
        return new Progress(level, xp, before.blocksMined());
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
