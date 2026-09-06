package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MariaMiningJournalIntegrationTest {
    @Test void recoveryInspectionSurvivesRestartWithoutResolvingOrAwardingAnything() throws Exception {
        var source = source(); var journal = new MariaMiningJournal(source); journal.migrate();
        var reserved = credit(); var recovery = credit(); var completed = credit();
        assertTrue(journal.reserve(reserved)); assertTrue(journal.reserve(recovery)); assertTrue(journal.reserve(completed));
        journal.needsRecovery(recovery.instanceId()); journal.complete(completed.instanceId());
        var restarted = new MariaMiningJournal(source);
        assertEquals(new MariaMiningJournal.Entry(reserved, MariaMiningJournal.State.RESERVED), restarted.find(reserved.instanceId()).orElseThrow());
        assertEquals(new MariaMiningJournal.Entry(recovery, MariaMiningJournal.State.RECOVERY_REQUIRED), restarted.find(recovery.instanceId()).orElseThrow());
        assertEquals(new MariaMiningJournal.Entry(completed, MariaMiningJournal.State.COMPLETED), restarted.find(completed.instanceId()).orElseThrow());
        assertTrue(restarted.find(UUID.randomUUID()).isEmpty());
        assertThrows(IllegalStateException.class, () -> new MiningCoordinator(restarted).credit(reserved, () -> fail("Inspection cannot release a reservation"), c -> fail()));
        assertThrows(IllegalStateException.class, () -> new MiningCoordinator(restarted).credit(recovery, () -> fail("Inspection cannot retry uncertain XP"), c -> fail()));
        assertFalse(restarted.pendingNotifications(1000).contains(recovery));
        assertFalse(restarted.pendingNotifications(1000).contains(reserved));
        assertFalse(restarted.pendingNotifications(1000).contains(completed));
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(completed.creditId()));
    }
    @Test void recoveryPagesUseExclusiveInstanceCursorAndExcludeCompletedRows() throws Exception {
        var journal = new MariaMiningJournal(source()); journal.migrate();
        var expected = new HashSet<UUID>();
        for (int i = 0; i < 5; i++) {
            var credit = credit(); journal.reserve(credit); expected.add(credit.instanceId());
            if (i % 2 == 0) journal.needsRecovery(credit.instanceId());
        }
        var completed = credit(); journal.reserve(completed); journal.complete(completed.instanceId());
        var seen = new HashSet<UUID>(); UUID cursor = null;
        // Existing tests share a disposable database. Scan all records, never assume an empty schema.
        for (int page = 0; ; page++) {
            assertTrue(page < 10000, "Recovery scan must advance");
            var entries = journal.pendingRecovery(cursor, 2);
            assertTrue(entries.size() <= 2);
            assertThrows(UnsupportedOperationException.class, () -> entries.add(new MariaMiningJournal.Entry(completed, MariaMiningJournal.State.COMPLETED)));
            if (entries.isEmpty()) break;
            for (var entry : entries) {
                assertNotEquals(MariaMiningJournal.State.COMPLETED, entry.state());
                var id = entry.credit().instanceId();
                if (cursor != null) assertTrue(id.toString().compareTo(cursor.toString()) > 0);
                assertTrue(seen.add(id), "No duplicate entries across exclusive pages");
                cursor = id;
            }
        }
        assertTrue(seen.containsAll(expected)); assertFalse(seen.contains(completed.instanceId()));
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(completed.creditId()));
    }
    @Test void invalidRecoveryBoundsRejectWithoutTouchingDatabase() {
        var journal = new MariaMiningJournal(null);
        for (int limit : List.of(-1, 0, 1001))
            assertThrows(IllegalArgumentException.class, () -> journal.pendingRecovery(null, limit));
        assertThrows(NullPointerException.class, () -> journal.find(null));
    }
    private DriverDataSource source() {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Disposable MariaDB required");
        return new DriverDataSource(url, "igear_test", "igear_test");
    }
    private MiningCredit credit() {
        return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "infinitygear:pickaxe", UUID.randomUUID(), -1, 70, 3, "minecraft:deepslate_diamond_ore",
                MiningCredit.Source.VEIN_MINER, "reset-2", true, true);
    }
    @Test void failedNotificationReplaysExactCreditAfterRestartWithoutAwardingXpAgain() throws Exception {
        var source = source();
        var journal = new MariaMiningJournal(source); journal.migrate();
        var credit = credit(); var ledger = new MariaMiningXpLedger(source);
        var receipt = commit(ledger, credit, null);
        var restarted = new MariaMiningJournal(source); restarted.migrate();
        assertTrue(restarted.pendingNotifications(1000).contains(credit));
        // Reading repeatedly or concurrently is safe but intentionally permits duplicate delivery.
        assertTrue(journal.pendingNotifications(1000).contains(credit));
        assertEquals(receipt, ledger.recoverOperation(credit, receipt.plan()).orElseThrow());
        restarted.notificationDelivered(credit.creditId());
        journal.notificationDelivered(credit.creditId());
        assertFalse(restarted.pendingNotifications(1000).contains(credit));
        assertEquals(1, ledger.findAccount(credit.pickaxeId()).orElseThrow().progress().blocksMined());
    }
    @Test void onlyCompletedCreditsEnterOutboxAndAcknowledge() throws Exception {
        var journal = new MariaMiningJournal(source()); journal.migrate(); journal.migrate();
        var reserved = credit(); var recovery = credit(); var completed = credit();
        assertTrue(journal.reserve(reserved)); assertTrue(journal.reserve(recovery)); assertTrue(journal.reserve(completed));
        journal.needsRecovery(recovery.instanceId());
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(reserved.creditId()));
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(recovery.creditId()));
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(UUID.randomUUID()));
        assertFalse(journal.pendingNotifications(1000).contains(reserved));
        assertFalse(journal.pendingNotifications(1000).contains(recovery));
        journal.complete(completed.instanceId());
        assertFalse(journal.pendingNotifications(1000).contains(completed));
        assertThrows(IllegalStateException.class, () -> journal.notificationDelivered(completed.creditId()));
        assertThrows(IllegalArgumentException.class, () -> journal.pendingNotifications(0));
        assertThrows(IllegalArgumentException.class, () -> journal.pendingNotifications(1001));
    }
    @Test void callbackCoordinatorCannotBypassAtomicReceiptBoundary() throws Exception {
        var journal = new MariaMiningJournal(source()); journal.migrate();
        var credit = credit();
        assertThrows(IllegalStateException.class, () -> new MiningCoordinator(journal).credit(credit, () -> fail("No callback XP"), c -> fail("No callback reward")));
        assertFalse(journal.pendingNotifications(1000).contains(credit));
    }
    @Test void durablePhysicalIdentitySurvivesCoordinatorRestartAndAllowsNextGeneration() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Disposable MariaDB required");
        var source = new DriverDataSource(url, "igear_test", "igear_test");
        var journal = new MariaMiningJournal(source); journal.migrate(); journal.migrate();
        var credit = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "infinitygear:pickaxe",
                UUID.randomUUID(), 1, 2, 3, "minecraft:stone", MiningCredit.Source.NORMAL, "reset-1", true, true);
        var ledger = new MariaMiningXpLedger(source); var first = commit(ledger, credit, null);
        var restarted = new MariaMiningXpLedger(source);
        assertEquals(first, restarted.apply(credit, first.plan()));
        var regenerated = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), credit.playerId(), credit.pickaxeId(), credit.profileId(),
                credit.worldId(), 1, 2, 3, "minecraft:stone", credit.source(), "reset-2", true, true);
        var second = commit(restarted, regenerated, first.account());
        assertEquals(2, second.account().progress().blocksMined());
    }
    private MariaMiningXpLedger.Receipt commit(MariaMiningXpLedger ledger, MiningCredit credit,
                                               com.infinitygear.mining.MiningXpPlan.Account account) throws Exception {
        if (account == null) account = ledger.initialize(credit.pickaxeId(), credit.profileId(), new com.infinitygear.mining.MiningXpPlan.Progress(0,0,0));
        return ledger.apply(credit, new com.infinitygear.mining.MiningXpPlan(credit.pickaxeId(), credit.profileId(), account.revision(), account.progress(), 1, List.of(100.0)));
    }
}
