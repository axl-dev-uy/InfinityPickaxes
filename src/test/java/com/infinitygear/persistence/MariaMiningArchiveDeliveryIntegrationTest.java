package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires an explicitly disposable fixture with Archive migrations 14 and 15. */
class MariaMiningArchiveDeliveryIntegrationTest {
    DataSource source;
    MariaMiningArchiveDelivery store;
    UUID historyPickaxeId;

    @AfterEach void removeSyntheticHistory() throws Exception {
        if (historyPickaxeId == null) return;
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE q FROM infinitygear_mining_archive_unsequenced q "
                    + "JOIN infinitygear_mining_credits m ON m.credit_id=q.credit_id WHERE m.generation_ref='history-fixture'");
            s.executeUpdate("DELETE o FROM infinitygear_mining_archive_order o "
                    + "JOIN infinitygear_mining_credits m ON m.credit_id=o.credit_id WHERE m.generation_ref='history-fixture'");
            s.executeUpdate("DELETE d FROM infinitygear_mining_archive_deliveries d "
                    + "JOIN infinitygear_mining_credits m ON m.credit_id=d.credit_id WHERE m.generation_ref='history-fixture'");
            s.executeUpdate("DELETE r FROM infinitygear_mining_xp_receipts r "
                    + "JOIN infinitygear_mining_credits m ON m.credit_id=r.credit_id WHERE m.generation_ref='history-fixture'");
            s.executeUpdate("DELETE FROM infinitygear_mining_credits WHERE generation_ref='history-fixture'");
        }
        try (var c = source.getConnection(); var s = c.prepareStatement(
                "DELETE FROM infinitygear_xp_accounts WHERE pickaxe_id=?")) {
            s.setString(1, historyPickaxeId.toString()); s.executeUpdate();
        }
    }

    @BeforeEach void fixture() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable MariaDB required");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        try (var c = source.getConnection(); var s = c.prepareStatement(
                "SELECT 1 FROM infinitygear_schema_migrations WHERE version=15")) {
            try (var rows = s.executeQuery()) {
                Assumptions.assumeTrue(rows.next(), "Archive migration 15 must be installed in the disposable fixture");
            }
        } catch (SQLException missing) {
            Assumptions.abort("Archive migration 15 is not installed");
        }
        store = new MariaMiningArchiveDelivery(source);
        store.requireSchema();
        // This class owns the Archive tables in an explicitly disposable test database.
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_unsequenced");
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_order");
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_deliveries");
            s.executeUpdate("UPDATE infinitygear_mining_archive_cursor SET next_delivery_sequence=1 WHERE subscription_id='archive-v1'");
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
        assertEquals(2, count("SELECT COUNT(*) FROM infinitygear_mining_archive_unsequenced WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "')"));
        var restarted = new MariaMiningArchiveDelivery(source);
        assertEquals(List.of(ordered.get(0)), restarted.pendingNotifications(1));
        assertEquals(2, count("SELECT COUNT(*) FROM infinitygear_mining_archive_order WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "')"));
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_unsequenced WHERE credit_id IN ('" + first.creditId() + "','" + second.creditId() + "')"));
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

    @Test void migrationRecoversUnsequencedCreditAndAcknowledgmentCursor() throws Exception {
        store.activate();
        var acknowledged = credit(); commit(acknowledged);
        store.pendingNotifications(1);
        store.notificationDelivered(acknowledged.creditId());
        var pending = credit(); commit(pending);
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_unsequenced");
            s.executeUpdate("DELETE FROM infinitygear_mining_archive_cursor");
            s.executeUpdate("DELETE FROM infinitygear_schema_migrations WHERE version=15");
        }
        store.migrate(); // same backfill used when upgrading an existing migration-14 installation
        assertEquals(1, count("SELECT COUNT(*) FROM infinitygear_mining_archive_unsequenced WHERE credit_id='" + pending.creditId() + "'"));
        assertEquals(List.of(pending), new MariaMiningArchiveDelivery(source).pendingNotifications(1));
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_unsequenced WHERE credit_id='" + pending.creditId() + "'"));
    }

    @Test void acknowledgedHistoryDoesNotMakeIdlePollingLockOutReceiptTransactions() throws Exception {
        seedAcknowledgedHistory(2_000);
        assertEquals(2_000, count("SELECT COUNT(*) FROM infinitygear_mining_archive_order"));
        assertEquals(0, count("SELECT COUNT(*) FROM infinitygear_mining_archive_unsequenced"));
        assertEquals(1, count("SELECT COUNT(*) FROM infinitygear_mining_archive_cursor c "
                + "WHERE c.next_delivery_sequence=(SELECT MAX(delivery_sequence)+1 FROM infinitygear_mining_archive_order)"));
        // The pending lookup must seek on the sequence primary key. The queue's
        // primary key contains only unsequenced work, not acknowledged history.
        try (var c = source.getConnection(); var s = c.createStatement(); var rows = s.executeQuery(
                "EXPLAIN SELECT o.credit_id FROM infinitygear_mining_archive_cursor curs "
                        + "STRAIGHT_JOIN infinitygear_mining_archive_order o FORCE INDEX(PRIMARY) "
                        + "ON o.delivery_sequence>=curs.next_delivery_sequence "
                        + "WHERE curs.subscription_id='archive-v1' ORDER BY o.delivery_sequence LIMIT 1")) {
            boolean rangeOnSequence = false;
            while (rows.next()) if ("o".equals(rows.getString("table")))
                rangeOnSequence = "PRIMARY".equals(rows.getString("key"))
                        && "range".equalsIgnoreCase(rows.getString("type"));
            assertTrue(rangeOnSequence, "Next-pending lookup must use a bounded sequence range");
        }
        try (var firstReceipt = source.getConnection()) {
            firstReceipt.setAutoCommit(false);
            try (var s = firstReceipt.prepareStatement("SELECT active FROM infinitygear_mining_archive_subscription "
                    + "WHERE subscription_id='archive-v1' LOCK IN SHARE MODE")) { s.executeQuery().close(); }
            var poll = CompletableFuture.supplyAsync(() -> store.pendingNotifications(1));
            var secondReceipt = CompletableFuture.runAsync(() -> {
                try (var c = source.getConnection()) {
                    c.setAutoCommit(false);
                    try (var s = c.prepareStatement("SELECT active FROM infinitygear_mining_archive_subscription "
                            + "WHERE subscription_id='archive-v1' LOCK IN SHARE MODE")) { s.executeQuery().close(); }
                    c.commit();
                } catch (SQLException failure) { throw new RuntimeException(failure); }
            });
            try {
                assertTrue(poll.get(2, TimeUnit.SECONDS).isEmpty(),
                        "An idle poll must finish while a receipt holds the shared enrollment lock");
                secondReceipt.get(2, TimeUnit.SECONDS);
            } finally { firstReceipt.commit(); }
        }
        store.activate();
        var next = credit(); commit(next);
        assertEquals(List.of(next), store.pendingNotifications(1));
        store.notificationDelivered(next.creditId());
        assertTrue(store.pendingNotifications(1).isEmpty());
    }

    private void seedAcknowledgedHistory(int size) throws Exception {
        UUID pickaxe = UUID.randomUUID();
        historyPickaxeId = pickaxe;
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        var ids = new ArrayList<UUID>(size);
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try (var account = c.prepareStatement("INSERT INTO infinitygear_xp_accounts "
                    + "(pickaxe_id,profile_id,revision,level_value,xp,blocks_mined) VALUES (?,?,0,0,0,0)")) {
                account.setString(1, pickaxe.toString()); account.setString(2, "infinitygear:pickaxe"); account.executeUpdate();
            }
            try (var credits = c.prepareStatement("INSERT INTO infinitygear_mining_credits "
                    + "(instance_id,credit_id,player_id,pickaxe_id,profile_id,world_id,block_x,block_y,block_z,original_data,source_type,generation_ref,state) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < size; i++) {
                    var id = UUID.randomUUID(); ids.add(id);
                    credits.setString(1, UUID.randomUUID().toString()); credits.setString(2, id.toString());
                    credits.setString(3, player.toString()); credits.setString(4, pickaxe.toString());
                    credits.setString(5, "infinitygear:pickaxe"); credits.setString(6, world.toString());
                    credits.setInt(7, i); credits.setInt(8, 64); credits.setInt(9, 0);
                    credits.setString(10, "minecraft:stone"); credits.setString(11, "NORMAL");
                    credits.setString(12, "history-fixture"); credits.setString(13, "COMPLETED"); credits.addBatch();
                }
                credits.executeBatch();
            }
            try (var receipts = c.prepareStatement("INSERT INTO infinitygear_mining_xp_receipts "
                    + "(credit_id,pickaxe_id,revision,plan) VALUES (?,?,?,?)");
                 var deliveries = c.prepareStatement("INSERT INTO infinitygear_mining_archive_deliveries "
                    + "(credit_id,digest_version,payload_sha256,acknowledged_at) VALUES (?,1,?,CURRENT_TIMESTAMP(6))");
                 var order = c.prepareStatement("INSERT INTO infinitygear_mining_archive_order(credit_id) VALUES (?)")) {
                for (int i = 0; i < size; i++) {
                    String id = ids.get(i).toString();
                    receipts.setString(1, id); receipts.setString(2, pickaxe.toString());
                    receipts.setInt(3, i + 1); receipts.setBytes(4, new byte[] { 0 }); receipts.addBatch();
                    deliveries.setString(1, id); deliveries.setBytes(2, new byte[32]); deliveries.addBatch();
                    order.setString(1, id); order.addBatch();
                }
                receipts.executeBatch(); deliveries.executeBatch(); order.executeBatch();
            }
            try (var cursor = c.prepareStatement("UPDATE infinitygear_mining_archive_cursor "
                    + "SET next_delivery_sequence=(SELECT MAX(delivery_sequence)+1 FROM infinitygear_mining_archive_order) "
                    + "WHERE subscription_id='archive-v1'")) { cursor.executeUpdate(); }
            c.commit();
        }
    }

    private int count(String sql) throws SQLException {
        try (var c = source.getConnection(); var s = c.createStatement(); var rows = s.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
}
