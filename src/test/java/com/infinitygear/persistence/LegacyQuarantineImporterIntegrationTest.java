package com.infinitygear.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyQuarantineImporterIntegrationTest {
    @TempDir Path temporary;

    @Test void preflightIsReadOnlyAndReportsStructuralProblems() throws Exception {
        UUID id = UUID.randomUUID();
        Path valid = sqlite("valid.db", id.toString(), "QUARANTINED", true);
        var first = LegacyQuarantineImporter.preflight(valid);
        var second = LegacyQuarantineImporter.preflight(valid);
        assertEquals(first.sourceId(), second.sourceId());
        assertEquals(1, first.schemaVersion()); assertEquals(1, first.identities().size());
        assertEquals(1, first.sightings().size()); assertTrue(first.structurallyValid());

        Path malformed = sqlite("malformed.db", "not-a-uuid", "UNKNOWN", false);
        var invalid = LegacyQuarantineImporter.preflight(malformed);
        assertFalse(invalid.structurallyValid());
        assertTrue(invalid.structuralIssues().stream().anyMatch(issue -> issue.startsWith("invalid-uuid:")));
        assertTrue(invalid.structuralIssues().stream().anyMatch(issue -> issue.startsWith("invalid-status:")));
    }

    @Test void dryRunImportAndRerunPreserveExactSourceEvidence() throws Exception {
        DataSource target = target();
        UUID id = UUID.randomUUID();
        var snapshot = LegacyQuarantineImporter.preflight(sqlite("import.db", id.toString(), "REVOKED", true));
        var importer = new LegacyQuarantineImporter(target);
        new MariaQuarantineAuthority(target).migrate();
        var dry = importer.importSnapshot(snapshot, false);
        assertTrue(dry.dryRun()); assertEquals(1, dry.inserted()); assertEquals(0, dry.conflicts());

        var imported = importer.importSnapshot(snapshot, true);
        assertEquals("IMPORTED", imported.state()); assertEquals(1, imported.inserted());
        assertDoesNotThrow(() -> new MariaQuarantineAuthority(target).requireAcceptedImport(snapshot.sourceId()));
        var record = new MariaQuarantineAuthority(target).find(id).orElseThrow();
        assertEquals("GEAR", record.trackedKind()); assertEquals("infinitygear:pickaxe", record.trackedType());
        assertEquals("REVOKED", record.status().name());

        var replay = importer.importSnapshot(snapshot, true);
        assertEquals(0, replay.inserted()); assertEquals(1, replay.matched()); assertEquals(0, replay.conflicts());
        try (var connection = target.getConnection()) {
            assertEquals(1, count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_import_sources WHERE source_id='"+snapshot.sourceId()+"'"));
            assertEquals(1, count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_import_identities WHERE source_id='"+snapshot.sourceId()+"'"));
            assertEquals(1, count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_import_sightings WHERE source_id='"+snapshot.sourceId()+"'"));
            assertEquals(1, count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_sightings WHERE tracked_id='"+id+"'"));
        }
    }

    @Test void semanticConflictPreservesRawInputAndPermanentlyFailsClosedOnRerun() throws Exception {
        DataSource target = target();
        var authority = new MariaQuarantineAuthority(target); authority.migrate();
        UUID id = UUID.randomUUID();
        try (var connection = target.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO infinitygear_quarantine_identities"
                        + "(tracked_id,status,first_detected,last_updated,reason,tracked_kind,tracked_type)"
                        + " VALUES (?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'different','GEAR','infinitygear:pickaxe')")) {
            insert.setString(1,id.toString()); insert.executeUpdate();
        }
        var snapshot = LegacyQuarantineImporter.preflight(sqlite("conflict.db",id.toString(),"REVOKED",true));
        var importer = new LegacyQuarantineImporter(target);
        var first = importer.importSnapshot(snapshot,true);
        assertEquals("CONFLICT_REVIEW_REQUIRED",first.state()); assertEquals(1,first.conflicts());
        assertThrows(IllegalStateException.class, () -> authority.requireAcceptedImport(snapshot.sourceId()));
        assertTrue(authority.isRestricted(id));
        long revision;
        try (var connection=target.getConnection()) {
            revision=count(connection,"SELECT authority_revision FROM infinitygear_quarantine_identities WHERE tracked_id='"+id+"'");
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_import_conflicts WHERE source_id='"+snapshot.sourceId()+"'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM infinitygear_quarantine_import_conflicts"
                    + " WHERE source_id='"+snapshot.sourceId()+"' AND existing_sha256 IS NOT NULL"
                    + " AND existing_sha256<>incoming_sha256"));
        }
        var replay=importer.importSnapshot(snapshot,true);
        assertEquals(1,replay.conflicts());
        try (var connection=target.getConnection()) {
            assertEquals(revision,count(connection,"SELECT authority_revision FROM infinitygear_quarantine_identities WHERE tracked_id='"+id+"'"));
        }
    }

    @Test void runtimeMutationsAreDurableIdempotentAndNeverWeakenRevocation() throws Exception {
        DataSource target = target();
        var authority = new MariaQuarantineAuthority(target); authority.migrate();
        UUID id = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        Instant first = Instant.parse("2026-09-10T12:00:00Z");
        String firstSightingId = id.toString().replace("-", "").repeat(2);
        var initialSighting = new MariaQuarantineAuthority.Sighting(firstSightingId, first,
                "player:test:slot=1", "scanner", "runtime:test-scan-1");

        authority.quarantine(id, "GEAR", "infinitygear:pickaxe", "duplicate", "scanner",
                first, List.of(initialSighting));
        authority.quarantine(id, "GEAR", "infinitygear:pickaxe", "duplicate", "scanner",
                first, List.of(initialSighting));
        assertEquals("QUARANTINED", authority.find(id).orElseThrow().status().name());
        assertEquals(1, sightings(target, id));
        UUID collidingIdentity = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> authority.quarantine(collidingIdentity,
                "GEAR", "infinitygear:pickaxe", "collision", "scanner", first,
                List.of(initialSighting)));
        assertTrue(authority.find(collidingIdentity).isEmpty());

        Instant revokedAt = first.plusSeconds(1);
        authority.revoke(id, "GEAR", "infinitygear:pickaxe", "operator resolution", "operator",
                replacement, revokedAt, List.of());
        var revoked = authority.find(id).orElseThrow();
        assertEquals("REVOKED", revoked.status().name());
        assertEquals(replacement, revoked.replacementUuid());
        assertDoesNotThrow(() -> authority.revoke(id, "GEAR", "infinitygear:pickaxe",
                "operator resolution", "operator", replacement, revokedAt, List.of()));
        assertThrows(IllegalStateException.class, () -> authority.revoke(id, "GEAR",
                "infinitygear:pickaxe", "conflicting retry", "operator", UUID.randomUUID(),
                revokedAt.plusMillis(1), List.of()));

        authority.quarantine(id, "GEAR", "infinitygear:armor", "late duplicate", "scanner",
                revokedAt.plusSeconds(1), List.of(new MariaQuarantineAuthority.Sighting(
                        replacement.toString().replace("-", "").repeat(2), revokedAt.plusSeconds(1),
                        "enderchest:test:slot=2", "scanner",
                        "runtime:test-scan-2")));
        var stillRevoked = authority.find(id).orElseThrow();
        assertEquals("REVOKED", stillRevoked.status().name());
        assertEquals(replacement, stillRevoked.replacementUuid());
        assertEquals("infinitygear:pickaxe", stillRevoked.trackedType());
        assertEquals(2, sightings(target, id));
    }

    private DataSource target() {
        String url=System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"Set INFINITYGEAR_TEST_JDBC_URL to a disposable MariaDB database");
        return new DriverDataSource(url,"igear_test","igear_test");
    }

    private Path sqlite(String name,String uuid,String status,boolean validSighting) throws Exception {
        Path path=temporary.resolve(name).toAbsolutePath();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()) {
            s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,applied_at INTEGER NOT NULL,description TEXT NOT NULL)");
            s.execute("INSERT INTO schema_migrations VALUES(1,1,'test')");
            s.execute("CREATE TABLE duplicate_pickaxes(uuid TEXT PRIMARY KEY,status TEXT NOT NULL,first_detected INTEGER NOT NULL,last_updated INTEGER NOT NULL,reason TEXT NOT NULL,resolved_by TEXT,replacement_uuid TEXT,tracked_kind TEXT NOT NULL,tracked_type TEXT NOT NULL)");
            s.execute("CREATE TABLE duplicate_sightings(id INTEGER PRIMARY KEY,uuid TEXT NOT NULL,observed_at INTEGER NOT NULL,location TEXT NOT NULL,actor TEXT)");
            try(var insert=c.prepareStatement("INSERT INTO duplicate_pickaxes VALUES(?,?,1000,2000,'legacy reason','operator',NULL,'GEAR','infinitygear:pickaxe')")) {
                insert.setString(1,uuid);insert.setString(2,status);insert.executeUpdate();
            }
            try(var insert=c.prepareStatement("INSERT INTO duplicate_sightings VALUES(1,?,1500,'player:test:slot=1','scanner')")) {
                insert.setString(1,validSighting?uuid:UUID.randomUUID().toString());insert.executeUpdate();
            }
        }
        return path;
    }

    private static long count(java.sql.Connection connection,String sql) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}
    }

    private static long sightings(DataSource target, UUID id) throws Exception {
        try (var connection = target.getConnection(); var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM infinitygear_quarantine_sightings WHERE tracked_id=?")) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
}
