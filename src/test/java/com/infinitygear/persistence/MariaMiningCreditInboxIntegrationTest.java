package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MariaMiningCreditInboxIntegrationTest {
    DataSource source;
    MariaMiningCreditInbox inbox;
    MariaMiningXpLedger xp;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Disposable MariaDB required");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        inbox = new MariaMiningCreditInbox(source);
        xp = new MariaMiningXpLedger(source);
        inbox.migrate(); inbox.migrate();
    }

    MiningCredit credit(UUID item) {
        return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), item,
                "infinitygear:pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone",
                MiningCredit.Source.NORMAL, "acceptance:" + UUID.randomUUID(), true, true);
    }

    @Test void migrationNineOwnsDurableInboxAndDuplicateIsAcceptedOnlyForIdenticalPayload() throws Exception {
        var credit = credit(UUID.randomUUID());
        assertTrue(inbox.accept(credit).inserted());
        assertFalse(new MariaMiningCreditInbox(source).accept(credit).inserted());
        var conflict = new MiningCredit(credit.creditId(), UUID.randomUUID(), credit.playerId(), credit.pickaxeId(),
                credit.profileId(), credit.worldId(), credit.x(), credit.y(), credit.z(), credit.originalBlockData(),
                credit.source(), credit.generation(), true, true);
        assertThrows(IllegalArgumentException.class, () -> inbox.accept(conflict));
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT COUNT(*) FROM infinitygear_schema_migrations WHERE version=9")) {
            assertTrue(row.next()); assertEquals(1, row.getInt(1));
        }
    }

    @Test void consumerCommitBeforeProducerAcknowledgementReplaysWithoutGrantingTwice() throws Exception {
        UUID item = UUID.randomUUID();
        var before = new MiningXpPlan.Progress(0, 0, 0);
        var adoption = xp.adopt(UUID.randomUUID(), UUID.randomUUID(), item, "infinitygear:pickaxe", before, "test");
        var credit = credit(item);
        var plan = new MiningXpPlan(item, "infinitygear:pickaxe", 0, before, 1, List.of(100.0));
        xp.apply(credit, plan);
        var outbox = new MariaMiningJournal(source);
        assertTrue(outbox.pendingNotifications(100).contains(credit));

        assertTrue(inbox.accept(credit).inserted()); // crash here: consumer committed, producer did not acknowledge
        assertTrue(outbox.pendingNotifications(100).contains(credit));
        assertFalse(new MariaMiningCreditInbox(source).accept(credit).inserted());
        outbox.notificationDelivered(credit.creditId());
        assertFalse(outbox.pendingNotifications(100).contains(credit));
        assertEquals(1, adoption.account().revision() + 1);
        assertEquals(1, xp.findAccount(item).orElseThrow().revision());
    }
}
