package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires an explicitly disposable fixture with the separately approved migration 14. */
class MariaMiningArchiveDeliveryIntegrationTest {
    DataSource source;
    MariaMiningArchiveDelivery store;

    @BeforeEach void fixture() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable MariaDB required");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        try (var c = source.getConnection(); var s = c.prepareStatement(
                "SELECT 1 FROM infinitygear_schema_migrations WHERE version=14")) {
            try (var rows = s.executeQuery()) {
                Assumptions.assumeTrue(rows.next(), "Reviewed migration 14 must be approved and installed in the disposable fixture");
            }
        } catch (SQLException missing) {
            Assumptions.abort("Reviewed migration 14 is not installed");
        }
        store = new MariaMiningArchiveDelivery(source);
        store.requireSchema();
        // This class owns the Archive tables in an explicitly disposable test database.
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_order");
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_deliveries");
            s.executeUpdate("UPDATE infinitygear_mining_archive_subscription SET active=FALSE,activated_at=NULL WHERE subscription_id='archive-v1'");
        }
    }

    private MiningCredit credit() {
        return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "test-" + UUID.randomUUID(), true, true);
    }

    private void commit(MiningCredit credit) throws Exception {
        var ledger = new MariaMiningXpLedger(source, store);
        var account = ledger.initialize(credit.pickaxeId(), credit.profileId(), new MiningXpPlan.Progress(0, 0, 0));
        ledger.apply(credit, new MiningXpPlan(credit.pickaxeId(), credit.profileId(), account.revision(),
                account.progress(), 1, List.of(100.0)));
    }

    @Test void activationDoesNotBackfillAndArchiveAckIsIndependentOfTheLocalInbox() throws Exception {
        var before = credit(); commit(before);
        assertTrue(store.pendingNotifications(1).isEmpty());
        store.activate(); store.activate();
        store.migrate(); // repeated migration must preserve durable activation
        var after = credit(); commit(after);
        assertEquals(List.of(after), store.pendingNotifications(1));
        var restarted = new MariaMiningArchiveDelivery(source);
        assertEquals(List.of(after), restarted.pendingNotifications(1));
        restarted.notificationDelivered(after.creditId());
        restarted.notificationDelivered(after.creditId());
        assertTrue(store.pendingNotifications(1).isEmpty());
        assertTrue(new MariaMiningJournal(source).pendingNotifications(1000).contains(after),
                "Archive acknowledgment must not acknowledge the local XP inbox");
        assertEquals(1, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id='" + after.creditId() + "' AND acknowledged_at IS NOT NULL"));
    }

    @Test void restartSequencesCommittedEnrollmentWithoutLossOrDuplication() throws Exception {
        store.activate();
        var first = credit(); var second = credit();
        commit(first); commit(second);
        var ordered = java.util.stream.Stream.of(first, second)
                .sorted(java.util.Comparator.comparing(c -> c.creditId().toString())).toList();
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_order WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "')"));
        var restarted = new MariaMiningArchiveDelivery(source);
        assertEquals(List.of(ordered.get(0)), restarted.pendingNotifications(1));
        assertEquals(2, count("SELECT COUNT(*) FROM infinitygear_mining_archive_order WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "')"));
        restarted.notificationDelivered(ordered.get(0).creditId());
        assertEquals(List.of(ordered.get(1)), restarted.pendingNotifications(1));
        restarted.notificationDelivered(ordered.get(1).creditId());
        assertTrue(new MariaMiningArchiveDelivery(source).pendingNotifications(1).isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "') AND acknowledged_at IS NOT NULL"));
    }

    @Test void changedPayloadAndMissingReceiptFailClosed() throws Exception {
        store.activate();
        var credit = credit(); commit(credit);
        try (var c = source.getConnection(); var s = c.prepareStatement(
                "UPDATE infinitygear_mining_credits SET block_x=block_x+1 WHERE credit_id=?")) {
            s.setString(1, credit.creditId().toString()); s.executeUpdate();
        }
        try {
            assertThrows(IllegalStateException.class, () -> store.pendingNotifications(1));
            assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id='" + credit.creditId() + "' AND acknowledged_at IS NOT NULL"));
        } finally {
            try (var c = source.getConnection(); var s = c.prepareStatement(
                    "UPDATE infinitygear_mining_credits SET block_x=? WHERE credit_id=?")) {
                s.setInt(1, credit.x()); s.setString(2, credit.creditId().toString()); s.executeUpdate();
            }
        }
        store.notificationDelivered(credit.creditId());
        var journal = new MariaMiningJournal(source);
        var receiptless = credit();
        var reserved = credit();
        var uncertain = credit();
        assertTrue(journal.reserve(receiptless)); journal.complete(receiptless.instanceId());
        assertTrue(journal.reserve(reserved));
        assertTrue(journal.reserve(uncertain)); journal.needsRecovery(uncertain.instanceId());
        assertTrue(store.pendingNotifications(1).isEmpty());
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id='" + receiptless.creditId() + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id IN ('" + reserved.creditId() + "','" + uncertain.creditId() + "')"));
    }

    @Test void sharedReceiptLockBlocksActivationUntilItsTransactionEnds() throws Exception {
        try (var receipt = source.getConnection()) {
            receipt.setAutoCommit(false);
            try (var s = receipt.prepareStatement("SELECT active FROM infinitygear_mining_archive_subscription WHERE subscription_id='archive-v1' LOCK IN SHARE MODE")) {
                s.executeQuery().close();
            }
            var activation = CompletableFuture.runAsync(store::activate);
            try {
                Thread.sleep(100);
                assertFalse(activation.isDone(), "Activation must wait for the receipt's shared lock");
            } finally { receipt.commit(); }
            activation.get(5, TimeUnit.SECONDS);
            var after = credit(); commit(after);
            assertEquals(List.of(after), store.pendingNotifications(1));
        }
    }

    @Test void upstreamPersistenceOutageLeavesTheCommittedCreditUnacknowledged() throws Exception {
        store.activate();
        var credit = credit(); commit(credit);
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("fixture outage"));
        assertThrows(IllegalStateException.class,
                () -> new MariaMiningArchiveDelivery(unavailable).pendingNotifications(1));
        assertEquals(List.of(credit), new MariaMiningArchiveDelivery(source).pendingNotifications(1));
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_deliveries WHERE credit_id='" + credit.creditId() + "' AND acknowledged_at IS NOT NULL"));
    }

    private int count(String sql) throws SQLException {
        try (var c = source.getConnection(); var s = c.createStatement(); var rows = s.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
}
