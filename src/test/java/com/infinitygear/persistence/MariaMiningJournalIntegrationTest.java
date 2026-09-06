package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MariaMiningJournalIntegrationTest {
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
        var credit = credit(); var xp = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new MiningCoordinator(journal).credit(credit,
                xp::incrementAndGet, c -> { throw new IllegalStateException("Interrupted delivery"); }));
        var restarted = new MariaMiningJournal(source); restarted.migrate();
        assertTrue(restarted.pendingNotifications(1000).contains(credit));
        // Reading repeatedly or concurrently is safe but intentionally permits duplicate delivery.
        assertTrue(journal.pendingNotifications(1000).contains(credit));
        assertFalse(new MiningCoordinator(restarted).credit(credit, xp::incrementAndGet, c -> fail()));
        restarted.notificationDelivered(credit.creditId());
        journal.notificationDelivered(credit.creditId());
        assertFalse(restarted.pendingNotifications(1000).contains(credit));
        assertEquals(1, xp.get());
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
        assertTrue(journal.pendingNotifications(1000).contains(completed));
        journal.notificationDelivered(completed.creditId());
        assertThrows(IllegalArgumentException.class, () -> journal.pendingNotifications(0));
        assertThrows(IllegalArgumentException.class, () -> journal.pendingNotifications(1001));
    }
    @Test void successfulNotificationIsAcknowledgedByCoordinator() throws Exception {
        var journal = new MariaMiningJournal(source()); journal.migrate();
        var credit = credit();
        assertTrue(new MiningCoordinator(journal).credit(credit, () -> {}, c -> assertEquals(credit, c)));
        assertFalse(journal.pendingNotifications(1000).contains(credit));
    }
    @Test void durablePhysicalIdentitySurvivesCoordinatorRestartAndAllowsNextGeneration() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Disposable MariaDB required");
        var source = new DriverDataSource(url, "igear_test", "igear_test");
        var journal = new MariaMiningJournal(source); journal.migrate(); journal.migrate();
        var credit = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "infinitygear:pickaxe",
                UUID.randomUUID(), 1, 2, 3, "minecraft:stone", MiningCredit.Source.NORMAL, "reset-1", true, true);
        var count = new AtomicInteger();
        assertTrue(new MiningCoordinator(journal).credit(credit, count::incrementAndGet, c -> {}));
        var restarted = new MiningCoordinator(new MariaMiningJournal(source));
        assertFalse(restarted.credit(credit, count::incrementAndGet, c -> fail()));
        var regenerated = new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), credit.playerId(), credit.pickaxeId(), credit.profileId(),
                credit.worldId(), 1, 2, 3, "minecraft:stone", credit.source(), "reset-2", true, true);
        assertTrue(restarted.credit(regenerated, count::incrementAndGet, c -> {}));
        assertEquals(2, count.get());
    }
}
