package com.infinitygear.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only an explicitly supplied disposable database; scoped rows remain inspectable. */
class MariaSingleServerCustodyIntegrationTest {
    private DataSource source;

    @BeforeEach void database() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set INFINITYGEAR_TEST_JDBC_URL to a disposable MariaDB database");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        new MariaBookLedger(source).migrate();
    }

    @Test void deploymentAndIdentityClaimsAreIdempotentForTheOwningServer() throws Exception {
        var custody = new MariaSingleServerCustody(source, "noxward-prison-01");
        custody.migrateAndClaimDeployment();
        custody.migrateAndClaimDeployment();
        UUID item = UUID.randomUUID(), first = UUID.randomUUID(), replay = UUID.randomUUID();
        custody.claim(item, "ARCHIVE_BOOK", first);
        custody.claim(item, "ARCHIVE_BOOK", first);
        custody.claim(item, "ARCHIVE_BOOK", replay);

        try (var connection = source.getConnection();
             var migration = connection.prepareStatement(
                     "SELECT COUNT(*) FROM infinitygear_schema_migrations WHERE version=12");
             var migrationRows = migration.executeQuery()) {
            assertTrue(migrationRows.next()); assertEquals(1, migrationRows.getInt(1));
        }
        try (var connection = source.getConnection();
             var row = connection.prepareStatement("SELECT server_id,epoch,state,last_operation_id"
                     + " FROM infinitygear_identity_custody WHERE tracked_id='" + item + "'");
             var result = row.executeQuery()) {
            assertTrue(result.next());
            assertEquals("noxward-prison-01", result.getString(1));
            assertEquals(custody.epoch(), result.getLong(2)); assertEquals("ACTIVE", result.getString(3));
            assertEquals(replay.toString(), result.getString(4));
        }
    }

    @Test void newerProcessWithTheSameStableServerIdFencesTheOlderEpoch() throws Exception {
        var oldProcess = new MariaSingleServerCustody(source, "noxward-prison-01");
        oldProcess.migrateAndClaimDeployment();
        var oldQuarantine = new MariaQuarantineAuthority(source, oldProcess);
        oldQuarantine.migrate();
        assertDoesNotThrow(oldQuarantine::listRestricted);
        UUID item = UUID.randomUUID();
        oldProcess.claim(item, "GEAR", UUID.randomUUID());

        var restartedProcess = new MariaSingleServerCustody(source, "noxward-prison-01");
        restartedProcess.migrateAndClaimDeployment();
        assertTrue(restartedProcess.epoch() > oldProcess.epoch());
        assertThrows(IllegalStateException.class, () -> oldProcess.verify(item, "GEAR"));
        assertThrows(IllegalStateException.class, () -> oldQuarantine.quarantine(UUID.randomUUID(),
                "GEAR", "infinitygear:pickaxe", "stale process", "test", Instant.now(), List.of()));
        assertThrows(IllegalStateException.class, oldQuarantine::listRestricted);
        restartedProcess.claim(item, "GEAR", UUID.randomUUID());
        assertDoesNotThrow(() -> restartedProcess.verify(item, "GEAR"));
        var currentQuarantine = new MariaQuarantineAuthority(source, restartedProcess);
        assertDoesNotThrow(currentQuarantine::listRestricted);
        assertDoesNotThrow(() -> currentQuarantine.quarantine(UUID.randomUUID(), "GEAR",
                "infinitygear:pickaxe", "current process", "test", Instant.now(), List.of()));
    }

    @Test void restartedOwnerMayResumeOnlyTheExactClaimingOperation() throws Exception {
        var oldProcess = new MariaSingleServerCustody(source, "noxward-prison-01");
        oldProcess.migrateAndClaimDeployment();
        UUID item = UUID.randomUUID(), operation = UUID.randomUUID();
        oldProcess.claim(item, "ARCHIVE_BOOK", operation);

        var restartedProcess = new MariaSingleServerCustody(source, "noxward-prison-01");
        restartedProcess.migrateAndClaimDeployment();
        assertThrows(IllegalStateException.class, () -> restartedProcess.verify(item, "ARCHIVE_BOOK"));
        assertThrows(IllegalStateException.class,
                () -> restartedProcess.verifyOrResume(item, "ARCHIVE_BOOK", UUID.randomUUID()));
        assertThrows(IllegalStateException.class,
                () -> restartedProcess.verifyOrResume(item, "GEAR", operation));

        assertDoesNotThrow(() -> restartedProcess.verifyOrResume(item, "ARCHIVE_BOOK", operation));
        assertDoesNotThrow(() -> restartedProcess.verify(item, "ARCHIVE_BOOK"));
        assertDoesNotThrow(() -> restartedProcess.verifyOrResume(item, "ARCHIVE_BOOK", operation));
    }

    @Test void anotherServerAndKindConflictCannotTakeAuthority() throws Exception {
        var owner = new MariaSingleServerCustody(source, "noxward-prison-01");
        owner.migrateAndClaimDeployment();
        UUID item = UUID.randomUUID();
        owner.claim(item, "GEAR", UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> owner.claim(item, "ARCHIVE_BOOK", UUID.randomUUID()));

        var other = new MariaSingleServerCustody(source, "other-server");
        assertThrows(IllegalStateException.class, other::migrateAndClaimDeployment);
        assertThrows(IllegalStateException.class,
                () -> other.claim(UUID.randomUUID(), "GEAR", UUID.randomUUID()));
    }

    @Test void invalidServerIdentityRejectsBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> new MariaSingleServerCustody(source, "Noxward Prison"));
    }
}
