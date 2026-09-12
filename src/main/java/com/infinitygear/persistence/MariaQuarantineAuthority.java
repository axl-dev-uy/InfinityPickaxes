package com.infinitygear.persistence;

import com.infinitypickaxes.core.duplicate.DuplicateRecord;
import com.infinitypickaxes.core.duplicate.DuplicateStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Blocking MariaDB quarantine authority. Call only on an integration worker. */
public final class MariaQuarantineAuthority {
    private final DataSource source;
    private final MariaSingleServerCustody custody;

    public MariaQuarantineAuthority(DataSource source) {
        this(source, null);
    }

    public MariaQuarantineAuthority(DataSource source, MariaSingleServerCustody custody) {
        this.source = Objects.requireNonNull(source, "source");
        this.custody = custody;
    }

    /** Restart-safe global migration 13. */
    public void migrate() throws Exception {
        new MariaBookLedger(source).migrate();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_identities ("
                    + "tracked_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "first_detected TIMESTAMP(6) NOT NULL,last_updated TIMESTAMP(6) NOT NULL,"
                    + "reason TEXT NOT NULL,resolved_by VARCHAR(256) NULL,"
                    + "replacement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "tracked_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "tracked_type VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "conflicted BOOLEAN NOT NULL DEFAULT FALSE,"
                    + "authority_revision BIGINT NOT NULL DEFAULT 1) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_sightings ("
                    + "sighting_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "tracked_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "observed_at TIMESTAMP(6) NOT NULL,location_text TEXT NOT NULL,actor VARCHAR(256) NULL,"
                    + "source_reference VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                    + "FOREIGN KEY(tracked_id) REFERENCES infinitygear_quarantine_identities(tracked_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_import_sources ("
                    + "source_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,"
                    + "source_path TEXT NOT NULL,db_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "wal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "shm_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                    + "schema_version INT NOT NULL,identity_count BIGINT NOT NULL,sighting_count BIGINT NOT NULL,"
                    + "state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "conflict_count BIGINT NOT NULL DEFAULT 0,"
                    + "imported_at TIMESTAMP(6) NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_import_identities ("
                    + "source_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,source_record_id VARCHAR(128) NOT NULL,"
                    + "uuid_text VARCHAR(128) NOT NULL,status_text VARCHAR(64) NOT NULL,first_detected BIGINT NOT NULL,"
                    + "last_updated BIGINT NOT NULL,reason_text TEXT NOT NULL,resolved_by TEXT NULL,replacement_uuid_text VARCHAR(128) NULL,"
                    + "tracked_kind_text VARCHAR(128) NOT NULL,tracked_type_text VARCHAR(512) NOT NULL,record_sha256 CHAR(64) NOT NULL,"
                    + "PRIMARY KEY(source_id,source_record_id),"
                    + "FOREIGN KEY(source_id) REFERENCES infinitygear_quarantine_import_sources(source_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_import_sightings ("
                    + "source_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,source_sighting_id BIGINT NOT NULL,"
                    + "uuid_text VARCHAR(128) NOT NULL,observed_at BIGINT NOT NULL,location_text TEXT NOT NULL,actor_text TEXT NULL,"
                    + "record_sha256 CHAR(64) NOT NULL,PRIMARY KEY(source_id,source_sighting_id),"
                    + "FOREIGN KEY(source_id) REFERENCES infinitygear_quarantine_import_sources(source_id)) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS infinitygear_quarantine_import_conflicts ("
                    + "source_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,source_record_id VARCHAR(128) NOT NULL,"
                    + "tracked_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,conflict_type VARCHAR(64) NOT NULL,"
                    + "existing_sha256 CHAR(64) NULL,incoming_sha256 CHAR(64) NOT NULL,detail_text TEXT NOT NULL,"
                    + "recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(source_id,source_record_id,conflict_type),"
                    + "FOREIGN KEY(source_id) REFERENCES infinitygear_quarantine_import_sources(source_id)) ENGINE=InnoDB");
            statement.executeUpdate("INSERT IGNORE INTO infinitygear_schema_migrations(version) VALUES (13)");
        }
    }

    public Optional<DuplicateRecord> find(UUID id) throws Exception {
        Objects.requireNonNull(id, "id");
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT * FROM infinitygear_quarantine_identities WHERE tracked_id=?")) {
            requireDeployment(connection);
            query.setString(1, id.toString());
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    public List<DuplicateRecord> listRestricted() throws Exception {
        List<DuplicateRecord> records = new ArrayList<>();
        try (var connection = source.getConnection()) {
            requireDeployment(connection);
            try (var query = connection.prepareStatement(
                    "SELECT * FROM infinitygear_quarantine_identities WHERE status<>'ACTIVE' OR conflicted=TRUE ORDER BY last_updated DESC");
                 var rows = query.executeQuery()) {
                while (rows.next()) records.add(read(rows));
            }
        }
        return List.copyOf(records);
    }

    public boolean isRestricted(UUID id) throws Exception {
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT status,conflicted FROM infinitygear_quarantine_identities WHERE tracked_id=?")) {
            requireDeployment(connection);
            query.setString(1, id.toString());
            try (var row = query.executeQuery()) {
                return row.next() && (!"ACTIVE".equals(row.getString(1)) || row.getBoolean(2));
            }
        }
    }

    /** Proves that the operator-approved legacy snapshot was imported without unresolved conflicts. */
    public void requireAcceptedImport(String sourceId) throws Exception {
        if (sourceId == null || !sourceId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A valid approved quarantine import source ID is required");
        }
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT state,conflict_count FROM infinitygear_quarantine_import_sources WHERE source_id=?")) {
            requireDeployment(connection);
            query.setString(1, sourceId);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Approved quarantine import is absent");
                if (!"IMPORTED".equals(row.getString(1)) || row.getLong(2) != 0) {
                    throw new IllegalStateException("Approved quarantine import requires manual conflict resolution");
                }
            }
        }
    }

    public record Sighting(String id, Instant observedAt, String location, String actor,
                           String sourceReference) {
        public Sighting {
            if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid sighting ID");
            Objects.requireNonNull(observedAt, "observedAt");
            if (location == null || location.isBlank()) throw new IllegalArgumentException("Invalid sighting location");
            if (sourceReference == null || sourceReference.isBlank() || sourceReference.length() > 512) {
                throw new IllegalArgumentException("Invalid sighting source");
            }
        }
    }

    /** Idempotent fail-closed quarantine write; REVOKED and conflicted authority never weakens. */
    public void quarantine(UUID id, String kind, String type, String reason, String actor,
                           Instant observedAt, List<Sighting> sightings) throws Exception {
        mutate(id, DuplicateStatus.QUARANTINED, kind, type, reason, actor, null,
                observedAt, sightings);
    }

    /** Idempotent permanent revocation; no method in this authority reactivates an identity. */
    public void revoke(UUID id, String kind, String type, String reason, String actor,
                       UUID replacement, Instant observedAt, List<Sighting> sightings) throws Exception {
        mutate(id, DuplicateStatus.REVOKED, kind, type, reason, actor, replacement,
                observedAt, sightings);
    }

    private void mutate(UUID id, DuplicateStatus requested, String kind, String type, String reason,
                        String actor, UUID replacement, Instant observedAt,
                        List<Sighting> sightings) throws Exception {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(observedAt, "observedAt"); sightings = List.copyOf(sightings);
        if (kind == null || !kind.matches("[A-Z][A-Z0-9_]{0,31}") || type == null
                || type.isBlank() || type.length() > 256 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Invalid quarantine mutation");
        }
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                requireDeployment(connection);
                boolean exists;
                String status = null;
                String existingReplacement = null;
                try (var query = connection.prepareStatement("SELECT status,replacement_id FROM infinitygear_quarantine_identities"
                        + " WHERE tracked_id=? FOR UPDATE")) {
                    query.setString(1, id.toString());
                    try (var row = query.executeQuery()) {
                        exists = row.next();
                        if (exists) { status = row.getString(1); existingReplacement = row.getString(2); }
                    }
                }
                if (!exists) {
                    try (var insert = connection.prepareStatement("INSERT INTO infinitygear_quarantine_identities"
                            + "(tracked_id,status,first_detected,last_updated,reason,resolved_by,replacement_id,tracked_kind,tracked_type)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1,id.toString()); insert.setString(2,requested.name());
                        insert.setTimestamp(3,java.sql.Timestamp.from(observedAt));
                        insert.setTimestamp(4,java.sql.Timestamp.from(observedAt)); insert.setString(5,reason);
                        insert.setString(6,actor); insert.setString(7,replacement==null?null:replacement.toString());
                        insert.setString(8,kind); insert.setString(9,type); insert.executeUpdate();
                    }
                } else if ("REVOKED".equals(status)) {
                    if (replacement != null && !replacement.toString().equals(existingReplacement)) {
                        throw new IllegalStateException("Revoked identity already records a different replacement");
                    }
                } else {
                    try (var update = connection.prepareStatement("UPDATE infinitygear_quarantine_identities SET status=?,"
                            + "last_updated=GREATEST(last_updated,?),reason=?,resolved_by=?,replacement_id=?,"
                            + "tracked_kind=?,tracked_type=?,authority_revision=authority_revision+1 WHERE tracked_id=?")) {
                        update.setString(1,requested.name()); update.setTimestamp(2,java.sql.Timestamp.from(observedAt));
                        update.setString(3,reason); update.setString(4,actor);
                        update.setString(5,replacement==null?null:replacement.toString()); update.setString(6,kind);
                        update.setString(7,type); update.setString(8,id.toString()); update.executeUpdate();
                    }
                }
                for (Sighting sighting : sightings) {
                    try (var insert = connection.prepareStatement("INSERT INTO infinitygear_quarantine_sightings"
                            + "(sighting_id,tracked_id,observed_at,location_text,actor,source_reference) VALUES (?,?,?,?,?,?)"
                            + " ON DUPLICATE KEY UPDATE sighting_id=VALUES(sighting_id)")) {
                        insert.setString(1,sighting.id()); insert.setString(2,id.toString());
                        insert.setTimestamp(3,java.sql.Timestamp.from(sighting.observedAt()));
                        insert.setString(4,sighting.location()); insert.setString(5,sighting.actor());
                        insert.setString(6,sighting.sourceReference()); insert.executeUpdate();
                    }
                    try (var query = connection.prepareStatement(
                            "SELECT tracked_id,observed_at,location_text,actor,source_reference"
                                    + " FROM infinitygear_quarantine_sightings WHERE sighting_id=?")) {
                        query.setString(1,sighting.id());
                        try (var row=query.executeQuery()) {
                            if (!row.next() || !id.toString().equals(row.getString(1))
                                    || !sighting.observedAt().equals(row.getTimestamp(2).toInstant())
                                    || !sighting.location().equals(row.getString(3))
                                    || !Objects.equals(sighting.actor(),row.getString(4))
                                    || !sighting.sourceReference().equals(row.getString(5))) {
                                throw new IllegalStateException("Sighting ID conflicts with different evidence");
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
    }

    private static DuplicateRecord read(ResultSet row) throws Exception {
        String replacement = row.getString("replacement_id");
        DuplicateStatus status = row.getBoolean("conflicted") ? DuplicateStatus.QUARANTINED
                : DuplicateStatus.valueOf(row.getString("status"));
        return new DuplicateRecord(UUID.fromString(row.getString("tracked_id")), status,
                row.getTimestamp("first_detected").toInstant(), row.getTimestamp("last_updated").toInstant(),
                row.getString("reason"), row.getString("resolved_by"),
                replacement == null ? null : UUID.fromString(replacement),
                row.getString("tracked_kind"), row.getString("tracked_type"));
    }

    static java.sql.Timestamp timestamp(long epochMillis) {
        return java.sql.Timestamp.from(Instant.ofEpochMilli(epochMillis));
    }

    private void requireDeployment(Connection connection) throws Exception {
        if (custody != null) custody.requireDeployment(connection);
    }
}
