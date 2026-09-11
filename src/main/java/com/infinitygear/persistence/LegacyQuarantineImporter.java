package com.infinitygear.persistence;

import com.infinitypickaxes.core.duplicate.DuplicateStatus;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit, blocking, operator-invoked SQLite-to-MariaDB quarantine importer. */
public final class LegacyQuarantineImporter {
    private final DataSource target;

    public LegacyQuarantineImporter(DataSource target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    public record FileEvidence(Path path, long size, String sha256) {
        public FileEvidence {
            path = path.toAbsolutePath().normalize();
            if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid file evidence");
            }
        }
    }

    public record IdentityRow(String uuidText, String status, long firstDetected, long lastUpdated,
                              String reason, String resolvedBy, String replacementUuid,
                              String trackedKind, String trackedType, String recordHash) { }
    public record SightingRow(long id, String uuidText, long observedAt, String location,
                              String actor, String recordHash) { }
    public record Snapshot(String sourceId, Path database, FileEvidence db,
                           Optional<FileEvidence> wal, Optional<FileEvidence> shm,
                           int schemaVersion, List<IdentityRow> identities,
                           List<SightingRow> sightings, List<String> structuralIssues) {
        public Snapshot {
            if (sourceId == null || !sourceId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source ID");
            database = database.toAbsolutePath().normalize();
            wal = Objects.requireNonNull(wal); shm = Objects.requireNonNull(shm);
            identities = List.copyOf(identities); sightings = List.copyOf(sightings);
            structuralIssues = List.copyOf(structuralIssues);
        }
        public boolean structurallyValid() { return structuralIssues.isEmpty(); }
    }

    public record Report(String sourceId, boolean dryRun, int identities, int sightings,
                         int inserted, int matched, int conflicts, int malformed,
                         String state, List<String> details) {
        public Report { details = List.copyOf(details); }
    }

    public static Snapshot preflight(Path database) throws Exception {
        Objects.requireNonNull(database, "database");
        Path db = database.toAbsolutePath().normalize();
        if (!database.isAbsolute() || !Files.isRegularFile(db)) {
            throw new IllegalArgumentException("An existing absolute SQLite database path is required");
        }
        FileEvidence dbEvidence = evidence(db);
        Optional<FileEvidence> wal = optionalEvidence(Path.of(db + "-wal"));
        Optional<FileEvidence> shm = optionalEvidence(Path.of(db + "-shm"));
        List<IdentityRow> identities = new ArrayList<>();
        List<SightingRow> sightings = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        int schema;
        String url = "jdbc:sqlite:file:" + db.toString().replace("\\", "/") + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url); var queryOnly = connection.createStatement()) {
            queryOnly.execute("PRAGMA query_only=ON");
            try (var query = connection.prepareStatement("SELECT COALESCE(MAX(version),0) FROM schema_migrations");
                 var row = query.executeQuery()) {
                schema = row.next() ? row.getInt(1) : 0;
            }
            try (var query = connection.prepareStatement("SELECT uuid,status,first_detected,last_updated,reason,"
                    + "resolved_by,replacement_uuid,tracked_kind,tracked_type FROM duplicate_pickaxes ORDER BY uuid");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    IdentityRow row = new IdentityRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                            rows.getLong(4), rows.getString(5), rows.getString(6), rows.getString(7),
                            rows.getString(8), rows.getString(9), hashFields(rows.getString(1), rows.getString(2),
                            Long.toString(rows.getLong(3)), Long.toString(rows.getLong(4)), rows.getString(5),
                            rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9)));
                    validate(row, issues);
                    identities.add(row);
                }
            }
            try (var query = connection.prepareStatement("SELECT id,uuid,observed_at,location,actor FROM duplicate_sightings ORDER BY id");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    SightingRow row = new SightingRow(id, rows.getString(2), rows.getLong(3), rows.getString(4),
                            rows.getString(5), hashFields(Long.toString(id), rows.getString(2),
                            Long.toString(rows.getLong(3)), rows.getString(4), rows.getString(5)));
                    validate(row, issues);
                    sightings.add(row);
                }
            }
        }
        var ids = identities.stream().map(IdentityRow::uuidText).collect(java.util.stream.Collectors.toSet());
        sightings.stream().filter(row -> !ids.contains(row.uuidText()))
                .forEach(row -> issues.add("orphan-sighting:" + row.id() + ":" + row.uuidText()));
        String sourceId = hashFields(dbEvidence.sha256(), wal.map(FileEvidence::sha256).orElse("ABSENT"),
                shm.map(FileEvidence::sha256).orElse("ABSENT"), Integer.toString(schema),
                Integer.toString(identities.size()), Integer.toString(sightings.size()));
        Snapshot result = new Snapshot(sourceId, db, dbEvidence, wal, shm, schema,
                identities, sightings, issues);
        Snapshot after = evidenceOnly(result);
        if (!sameEvidence(result, after)) throw new IllegalStateException("SQLite file set changed during preflight");
        return result;
    }

    public Report importSnapshot(Snapshot approved, boolean apply) throws Exception {
        Objects.requireNonNull(approved, "approved");
        Snapshot current = preflight(approved.database());
        if (!sameEvidence(approved, current) || !approved.sourceId().equals(current.sourceId())) {
            throw new IllegalStateException("SQLite source no longer matches the approved preflight");
        }
        if (!current.structurallyValid()) {
            return new Report(current.sourceId(), !apply, current.identities().size(), current.sightings().size(),
                    0, 0, 0, current.structuralIssues().size(), "STRUCTURAL_CONFLICT", current.structuralIssues());
        }
        if (!apply) return analyze(current);
        new MariaQuarantineAuthority(target).migrate();
        return apply(current);
    }

    private Report analyze(Snapshot snapshot) throws Exception {
        List<String> details = new ArrayList<>();
        int inserted = 0, matched = 0, conflicts = 0;
        try (var connection = target.getConnection()) {
            if (!tableExists(connection, "infinitygear_quarantine_identities")) {
                return new Report(snapshot.sourceId(), true, snapshot.identities().size(), snapshot.sightings().size(),
                        snapshot.identities().size(), 0, 0, 0, "DRY_RUN", List.of("target-schema-absent"));
            }
            for (IdentityRow row : snapshot.identities()) {
                try (var query = connection.prepareStatement("SELECT status,first_detected,last_updated,reason,resolved_by,"
                        + "replacement_id,tracked_kind,tracked_type FROM infinitygear_quarantine_identities WHERE tracked_id=?")) {
                    query.setString(1, row.uuidText());
                    try (var existing = query.executeQuery()) {
                        if (!existing.next()) inserted++;
                        else if (equivalent(existing, row)) matched++;
                        else { conflicts++; details.add("semantic-conflict:" + row.uuidText()); }
                    }
                }
            }
        }
        return new Report(snapshot.sourceId(), true, snapshot.identities().size(), snapshot.sightings().size(),
                inserted, matched, conflicts, 0, conflicts == 0 ? "DRY_RUN" : "CONFLICT_REVIEW_REQUIRED", details);
    }

    private Report apply(Snapshot snapshot) throws Exception {
        List<String> details = new ArrayList<>();
        int inserted = 0, matched = 0, conflicts = 0;
        try (var connection = target.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                insertSource(connection, snapshot);
                for (IdentityRow row : snapshot.identities()) {
                    insertRawIdentity(connection, snapshot.sourceId(), row);
                    if (hasRecordedConflict(connection, snapshot.sourceId(), row.uuidText())) {
                        conflicts++; details.add("semantic-conflict:" + row.uuidText());
                        continue;
                    }
                    try (var query = connection.prepareStatement("SELECT status,first_detected,last_updated,reason,resolved_by,"
                            + "replacement_id,tracked_kind,tracked_type FROM infinitygear_quarantine_identities WHERE tracked_id=? FOR UPDATE")) {
                        query.setString(1, row.uuidText());
                        try (var existing = query.executeQuery()) {
                            if (!existing.next()) { insertAuthority(connection, row); inserted++; }
                            else if (equivalent(existing, row)) matched++;
                            else {
                                conflicts++; details.add("semantic-conflict:" + row.uuidText());
                                insertConflict(connection, snapshot.sourceId(), row, "SEMANTIC_MISMATCH",
                                        existingHash(existing, row.uuidText()));
                                try (var restrict = connection.prepareStatement("UPDATE infinitygear_quarantine_identities"
                                        + " SET status='QUARANTINED',conflicted=TRUE,authority_revision=authority_revision+1"
                                        + " WHERE tracked_id=?")) {
                                    restrict.setString(1, row.uuidText()); restrict.executeUpdate();
                                }
                            }
                        }
                    }
                }
                for (SightingRow row : snapshot.sightings()) {
                    insertRawSighting(connection, snapshot.sourceId(), row);
                    insertSighting(connection, snapshot.sourceId(), row);
                }
                try (var update = connection.prepareStatement("UPDATE infinitygear_quarantine_import_sources SET state=?,"
                        + "conflict_count=?,imported_at=CURRENT_TIMESTAMP(6) WHERE source_id=?")) {
                    update.setString(1, conflicts == 0 ? "IMPORTED" : "CONFLICT");
                    update.setInt(2, conflicts); update.setString(3, snapshot.sourceId()); update.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        return new Report(snapshot.sourceId(), false, snapshot.identities().size(), snapshot.sightings().size(),
                inserted, matched, conflicts, 0, conflicts == 0 ? "IMPORTED" : "CONFLICT_REVIEW_REQUIRED", details);
    }

    private static void insertSource(Connection c, Snapshot s) throws Exception {
        try (var insert = c.prepareStatement("INSERT IGNORE INTO infinitygear_quarantine_import_sources"
                + "(source_id,source_path,db_sha256,wal_sha256,shm_sha256,schema_version,identity_count,sighting_count,state)"
                + " VALUES (?,?,?,?,?,?,?,?, 'PREPARED')")) {
            insert.setString(1, s.sourceId()); insert.setString(2, s.database().toString()); insert.setString(3, s.db().sha256());
            insert.setString(4, s.wal().map(FileEvidence::sha256).orElse(null));
            insert.setString(5, s.shm().map(FileEvidence::sha256).orElse(null)); insert.setInt(6, s.schemaVersion());
            insert.setInt(7, s.identities().size()); insert.setInt(8, s.sightings().size()); insert.executeUpdate();
        }
        try (var query = c.prepareStatement("SELECT db_sha256,wal_sha256,shm_sha256,schema_version,identity_count,sighting_count"
                + " FROM infinitygear_quarantine_import_sources WHERE source_id=? FOR UPDATE")) {
            query.setString(1, s.sourceId());
            try (var row = query.executeQuery()) {
                if (!row.next() || !Objects.equals(row.getString(1), s.db().sha256())
                        || !Objects.equals(row.getString(2), s.wal().map(FileEvidence::sha256).orElse(null))
                        || !Objects.equals(row.getString(3), s.shm().map(FileEvidence::sha256).orElse(null))
                        || row.getInt(4) != s.schemaVersion() || row.getInt(5) != s.identities().size()
                        || row.getInt(6) != s.sightings().size()) throw new IllegalStateException("Import source ID conflict");
            }
        }
    }

    private static void insertRawIdentity(Connection c, String source, IdentityRow r) throws Exception {
        try (var insert = c.prepareStatement("INSERT IGNORE INTO infinitygear_quarantine_import_identities VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i=1; insert.setString(i++,source); insert.setString(i++,r.uuidText()); insert.setString(i++,r.uuidText());
            insert.setString(i++,r.status()); insert.setLong(i++,r.firstDetected()); insert.setLong(i++,r.lastUpdated());
            insert.setString(i++,r.reason()); insert.setString(i++,r.resolvedBy()); insert.setString(i++,r.replacementUuid());
            insert.setString(i++,r.trackedKind()); insert.setString(i++,r.trackedType()); insert.setString(i,r.recordHash());
            insert.executeUpdate();
        }
        requireRawHash(c,"infinitygear_quarantine_import_identities","source_record_id",source,r.uuidText(),r.recordHash());
    }

    private static void insertRawSighting(Connection c, String source, SightingRow r) throws Exception {
        try (var insert = c.prepareStatement("INSERT IGNORE INTO infinitygear_quarantine_import_sightings VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1,source); insert.setLong(2,r.id()); insert.setString(3,r.uuidText());
            insert.setLong(4,r.observedAt()); insert.setString(5,r.location()); insert.setString(6,r.actor());
            insert.setString(7,r.recordHash()); insert.executeUpdate();
        }
        requireRawHash(c,"infinitygear_quarantine_import_sightings","source_sighting_id",source,
                Long.toString(r.id()),r.recordHash());
    }

    private static void insertAuthority(Connection c, IdentityRow r) throws Exception {
        try (var insert = c.prepareStatement("INSERT INTO infinitygear_quarantine_identities"
                + "(tracked_id,status,first_detected,last_updated,reason,resolved_by,replacement_id,tracked_kind,tracked_type)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1,r.uuidText()); insert.setString(2,r.status());
            insert.setTimestamp(3,MariaQuarantineAuthority.timestamp(r.firstDetected()));
            insert.setTimestamp(4,MariaQuarantineAuthority.timestamp(r.lastUpdated())); insert.setString(5,r.reason());
            insert.setString(6,r.resolvedBy()); insert.setString(7,r.replacementUuid());
            insert.setString(8,r.trackedKind()); insert.setString(9,r.trackedType()); insert.executeUpdate();
        }
    }

    private static void insertSighting(Connection c, String source, SightingRow r) throws Exception {
        String sightingId = hashFields(source, Long.toString(r.id()));
        String reference = "legacy-sqlite:"+source+":"+r.id();
        try (var insert = c.prepareStatement("INSERT INTO infinitygear_quarantine_sightings"
                + "(sighting_id,tracked_id,observed_at,location_text,actor,source_reference) VALUES (?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE sighting_id=VALUES(sighting_id)")) {
            insert.setString(1,sightingId); insert.setString(2,r.uuidText());
            insert.setTimestamp(3,MariaQuarantineAuthority.timestamp(r.observedAt())); insert.setString(4,r.location());
            insert.setString(5,r.actor()); insert.setString(6,reference); insert.executeUpdate();
        }
        try (var query=c.prepareStatement("SELECT tracked_id,observed_at,location_text,actor,source_reference"
                + " FROM infinitygear_quarantine_sightings WHERE sighting_id=?")) {
            query.setString(1,sightingId);
            try (var row=query.executeQuery()) {
                if (!row.next() || !r.uuidText().equals(row.getString(1))
                        || row.getTimestamp(2).toInstant().toEpochMilli()!=r.observedAt()
                        || !r.location().equals(row.getString(3)) || !Objects.equals(r.actor(),row.getString(4))
                        || !reference.equals(row.getString(5))) {
                    throw new IllegalStateException("Legacy sighting ID conflicts with different evidence: "+r.id());
                }
            }
        }
    }

    private static void insertConflict(Connection c, String source, IdentityRow r, String type,
                                       String existingHash) throws Exception {
        try (var insert = c.prepareStatement("INSERT IGNORE INTO infinitygear_quarantine_import_conflicts"
                + "(source_id,source_record_id,tracked_id,conflict_type,existing_sha256,incoming_sha256,detail_text)"
                + " VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1,source); insert.setString(2,r.uuidText()); insert.setString(3,r.uuidText());
            insert.setString(4,type); insert.setString(5,existingHash); insert.setString(6,r.recordHash());
            insert.setString(7,"Existing MariaDB authority differs; identity forced to QUARANTINED for manual resolution");
            insert.executeUpdate();
        }
    }

    private static boolean hasRecordedConflict(Connection c, String source, String record) throws Exception {
        try (var query = c.prepareStatement("SELECT 1 FROM infinitygear_quarantine_import_conflicts"
                + " WHERE source_id=? AND source_record_id=? LIMIT 1")) {
            query.setString(1, source); query.setString(2, record);
            try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    private static void requireRawHash(Connection c,String table,String keyColumn,String source,String key,
                                       String expected) throws Exception {
        try(var query=c.prepareStatement("SELECT record_sha256 FROM "+table+" WHERE source_id=? AND "+keyColumn+"=?")) {
            query.setString(1,source); query.setString(2,key);
            try(var row=query.executeQuery()) {
                if(!row.next()||!expected.equals(row.getString(1)))
                    throw new IllegalStateException("Raw import evidence conflicts for "+table+":"+key);
            }
        }
    }

    private static boolean equivalent(java.sql.ResultSet e, IdentityRow r) throws Exception {
        return Objects.equals(e.getString(1),r.status())
                && e.getTimestamp(2).toInstant().toEpochMilli()==r.firstDetected()
                && e.getTimestamp(3).toInstant().toEpochMilli()==r.lastUpdated()
                && Objects.equals(e.getString(4),r.reason()) && Objects.equals(e.getString(5),r.resolvedBy())
                && Objects.equals(e.getString(6),r.replacementUuid()) && Objects.equals(e.getString(7),r.trackedKind())
                && Objects.equals(e.getString(8),r.trackedType());
    }

    private static String existingHash(java.sql.ResultSet e, String uuid) throws Exception {
        return hashFields(uuid, e.getString(1), Long.toString(e.getTimestamp(2).toInstant().toEpochMilli()),
                Long.toString(e.getTimestamp(3).toInstant().toEpochMilli()), e.getString(4), e.getString(5),
                e.getString(6), e.getString(7), e.getString(8));
    }

    private static boolean tableExists(Connection c, String table) throws Exception {
        try (var row = c.getMetaData().getTables(c.getCatalog(),null,table,null)) { return row.next(); }
    }

    private static void validate(IdentityRow r, List<String> issues) {
        try { UUID.fromString(r.uuidText()); } catch (RuntimeException invalid) { issues.add("invalid-uuid:"+r.uuidText()); }
        try { DuplicateStatus.valueOf(r.status()); } catch (RuntimeException invalid) { issues.add("invalid-status:"+r.uuidText()+":"+r.status()); }
        if (r.firstDetected()<0 || r.lastUpdated()<r.firstDetected()) issues.add("invalid-timestamps:"+r.uuidText());
        if (r.reason()==null || r.reason().isBlank()) issues.add("missing-reason:"+r.uuidText());
        if (r.trackedKind()==null || !r.trackedKind().matches("[A-Z][A-Z0-9_]{0,31}")) issues.add("invalid-kind:"+r.uuidText());
        if (r.trackedType()==null || r.trackedType().isBlank() || r.trackedType().length()>256) issues.add("invalid-type:"+r.uuidText());
        if (r.replacementUuid()!=null) try { UUID.fromString(r.replacementUuid()); }
        catch (RuntimeException invalid) { issues.add("invalid-replacement:"+r.uuidText()); }
        if (r.resolvedBy()!=null && r.resolvedBy().length()>256) issues.add("invalid-resolver:"+r.uuidText());
    }
    private static void validate(SightingRow r, List<String> issues) {
        try { UUID.fromString(r.uuidText()); } catch (RuntimeException invalid) { issues.add("invalid-sighting-uuid:"+r.id()); }
        if (r.id()<0 || r.observedAt()<0) issues.add("invalid-sighting-time:"+r.id());
        if (r.location()==null || r.location().isBlank()) issues.add("invalid-sighting-location:"+r.id());
        if (r.actor()!=null && r.actor().length()>256) issues.add("invalid-sighting-actor:"+r.id());
    }

    private static Snapshot evidenceOnly(Snapshot s) throws Exception {
        return new Snapshot(s.sourceId(),s.database(),evidence(s.database()),optionalEvidence(Path.of(s.database()+"-wal")),
                optionalEvidence(Path.of(s.database()+"-shm")),s.schemaVersion(),s.identities(),s.sightings(),s.structuralIssues());
    }
    private static boolean sameEvidence(Snapshot a, Snapshot b) {
        return a.db().equals(b.db()) && a.wal().equals(b.wal()) && a.shm().equals(b.shm());
    }
    private static Optional<FileEvidence> optionalEvidence(Path path) throws Exception {
        return Files.exists(path) ? Optional.of(evidence(path)) : Optional.empty();
    }
    private static FileEvidence evidence(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[8192]; for(int read;(read=in.read(buffer))>=0;) digest.update(buffer,0,read);}
        return new FileEvidence(path,Files.size(path),HexFormat.of().formatHex(digest.digest()));
    }
    private static String hashFields(String... fields) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String field:fields){byte[] bytes=field==null?new byte[0]:field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(field==null?-1:bytes.length).array()); if(field!=null)digest.update(bytes);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
