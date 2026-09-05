package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MariaMiningJournalIntegrationTest {
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
