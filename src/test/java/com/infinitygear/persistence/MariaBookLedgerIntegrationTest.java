package com.infinitygear.persistence;

import com.infinitygear.api.v1.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an explicitly supplied disposable database, never the plugin's bootstrap config. */
class MariaBookLedgerIntegrationTest {
    MariaBookLedger ledger;
    @BeforeEach void database() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Set INFINITYGEAR_TEST_JDBC_URL to a disposable MariaDB database");
        ledger = new MariaBookLedger(new DriverDataSource(url, "igear_test", "igear_test"));
        ledger.migrate(); ledger.migrate();
    }
    BookLedger.Issue request(UUID op, UUID reward, String value) {
        return new BookLedger.Issue(op, reward, "minecraft:fortune", 1, "archive:test", new BigDecimal(value));
    }
    @Test void concurrentRetriesRecoverSameIdentityAndSeparateRewardsAreDistinct() throws Exception {
        var request = request(UUID.randomUUID(), UUID.randomUUID(), "0.1");
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<BookLedger.Receipt>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> ledger.issue(request)));
            Set<UUID> identities = new HashSet<>();
            for (var future : futures) identities.add(future.get().bookId());
            assertEquals(1, identities.size());
            assertFalse(identities.contains(ledger.issue(request(request.operationId(), UUID.randomUUID(), "0.1")).bookId()));
        }
        assertThrows(IllegalArgumentException.class, () -> ledger.issue(request(request.operationId(), request.rewardId(), "0.2")));
        assertEquals(request, ledger.find(ledger.issue(request).bookId()).orElseThrow().issue());
    }
    @Test void fusionRetiresSourcesPreservesExactValueAndReplaysAfterRepositoryRestart() throws Exception {
        var a = ledger.issue(request(UUID.randomUUID(), UUID.randomUUID(), "0.1"));
        var b = ledger.issue(request(UUID.randomUUID(), UUID.randomUUID(), "0.2"));
        var op = new ProvenanceTransition.Request(UUID.randomUUID(), ProvenancePolicy.Operation.PAIR_FUSION,
                List.of(a.bookId(), b.bookId()), List.of(new ProvenanceTransition.Output("minecraft:fortune", 2, "archive:fused")));
        ProvenancePolicy testOnlyPolicy = (operation, sources, count) -> new ProvenancePolicy.Decision(true, List.of(new BigDecimal("0.3")), "explicit test policy");
        assertThrows(IllegalStateException.class, () -> ledger.transition(op, ProvenancePolicy.unresolved()));
        assertFalse(ledger.find(a.bookId()).orElseThrow().consumed());
        var result = ledger.transition(op, testOnlyPolicy);
        assertEquals(new BigDecimal("0.300000000000000000"), result.getFirst().issue().sourceValue());
        assertNotEquals(a.bookId(), result.getFirst().bookId());
        assertTrue(ledger.find(a.bookId()).orElseThrow().consumed()); assertTrue(ledger.find(b.bookId()).orElseThrow().consumed());
        database();
        assertEquals(result, ledger.transition(op, ProvenancePolicy.unresolved()));
        var reused = new ProvenanceTransition.Request(UUID.randomUUID(), op.operation(), op.sourceBooks(), op.outputs());
        assertThrows(IllegalArgumentException.class, () -> ledger.transition(reused, testOnlyPolicy));
        assertThrows(IllegalArgumentException.class, () -> ledger.issue(request(op.operationId(), UUID.randomUUID(), "1")));
    }
    @Test void bulkFusionRollbackPreventsInflationAndDuplicateParents() throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) ids.add(ledger.issue(request(UUID.randomUUID(), UUID.randomUUID(), "0.1")).bookId());
        var op = new ProvenanceTransition.Request(UUID.randomUUID(), ProvenancePolicy.Operation.BULK_FUSION, ids,
                List.of(new ProvenanceTransition.Output("minecraft:fortune", 3, "archive:bulk")));
        assertThrows(IllegalArgumentException.class, () -> ledger.transition(op, (o,s,n) -> new ProvenancePolicy.Decision(true, List.of(BigDecimal.ONE), "bad policy")));
        for (UUID id : ids) assertFalse(ledger.find(id).orElseThrow().consumed());
        var output = ledger.transition(op, (o,s,n) -> new ProvenancePolicy.Decision(true, List.of(new BigDecimal("0.4")), "test policy"));
        assertEquals(1, output.size());
        for (UUID id : ids) assertTrue(ledger.find(id).orElseThrow().consumed());
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceTransition.Request(UUID.randomUUID(), op.operation(), List.of(ids.getFirst(), ids.getFirst()), op.outputs()));
    }
}
