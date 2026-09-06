package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import com.infinitygear.mining.MiningXpPlan;
import com.infinitygear.mining.MiningXpPlan.*;
import com.infinitygear.mining.MiningXpParticipant;
import com.infinitygear.mining.MiningXpItemProjection;
import com.infinitygear.integration.IntegrationTasks;
import org.bukkit.Bukkit;
import static org.mockito.Mockito.mockStatic;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MariaMiningXpLedgerIntegrationTest {
    DataSource source; MariaMiningXpLedger ledger; Account account;
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INFINITYGEAR_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Disposable MariaDB required");
        source = new DriverDataSource(url, "igear_test", "igear_test");
        ledger = new MariaMiningXpLedger(source); ledger.migrate(); ledger.migrate();
        account = ledger.initialize(UUID.randomUUID(), "infinitygear:pickaxe", new Progress(0, 90, 7));
    }
    MiningCredit credit() {
        return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), account.pickaxeId(),
                account.profileId(), UUID.randomUUID(), 1, 2, 3, "minecraft:stone", MiningCredit.Source.VEIN_MINER, "reset-1", true, true);
    }
    MiningXpPlan plan(Account before) {
        return new MiningXpPlan(before.pickaxeId(), before.profileId(), before.revision(), before.progress(), 260, List.of(100.0, 200.0, 400.0));
    }
    @Test void simultaneousRetriesCommitXpReceiptAndOutboxExactlyOnce() throws Exception {
        var credit = credit(); var plan = plan(account);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<MariaMiningXpLedger.Receipt>>();
            for (int i = 0; i < 8; i++) jobs.add(workers.submit(() -> ledger.apply(credit, plan)));
            var receipt = jobs.getFirst().get();
            for (var job : jobs) assertEquals(receipt, job.get());
            assertEquals(new Progress(2, 50, 8), receipt.account().progress());
            assertEquals(1, receipt.account().revision());
            var restarted = new MariaMiningXpLedger(source);
            assertEquals(receipt, restarted.findReceipt(credit.creditId()).orElseThrow());
            assertEquals(receipt, restarted.findReceipt(account.pickaxeId(), 1).orElseThrow());
            assertTrue(restarted.findReceipt(account.pickaxeId(), 2).isEmpty());
            assertEquals(receipt.account(), restarted.findAccount(account.pickaxeId()).orElseThrow());
            assertTrue(new MariaMiningJournal(source).pendingNotifications(1000).contains(credit));
        }
    }
    @Test void staleDifferentCreditAndChangedRetryCannotOverwriteAccount() throws Exception {
        var credit = credit(); var plan = plan(account); var receipt = ledger.apply(credit, plan);
        var other = credit();
        assertThrows(IllegalStateException.class, () -> ledger.apply(other, plan));
        assertTrue(new MariaMiningJournal(source).find(other.instanceId()).isEmpty());
        var changed = new MiningXpPlan(plan.pickaxeId(), plan.profileId(), 0, plan.before(), 1, plan.requiredXp());
        assertThrows(IllegalArgumentException.class, () -> ledger.apply(credit, changed));
        assertEquals(receipt.account(), ledger.findAccount(account.pickaxeId()).orElseThrow());
        assertEquals(receipt.account(), ledger.initialize(account.pickaxeId(), account.profileId(), new Progress(0, 0, 0)));
    }
    @Test void oldReceiptRemainsRecoverableAfterLaterCreditAndConfigurationChange() throws Exception {
        var first = credit(); var firstPlan = plan(account); var receipt = ledger.apply(first, firstPlan);
        var next = credit(); var nextPlan = new MiningXpPlan(account.pickaxeId(), account.profileId(), 1, receipt.account().progress(), 2, List.of(10.0, 20.0, 1000.0));
        var latest = ledger.apply(next, nextPlan);
        assertEquals(receipt, ledger.apply(first, firstPlan));
        assertEquals(latest.account(), ledger.findAccount(account.pickaxeId()).orElseThrow());
    }
    @Test void existingAmbiguousReservationCannotBeReinterpretedAsUnpaidXp() throws Exception {
        var credit = credit(); var journal = new MariaMiningJournal(source);
        assertTrue(journal.reserve(credit)); journal.needsRecovery(credit.instanceId());
        assertThrows(SQLException.class, () -> ledger.apply(credit, plan(account)));
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
        assertTrue(ledger.findReceipt(credit.creditId()).isEmpty());
        assertEquals(MariaMiningJournal.State.RECOVERY_REQUIRED, journal.find(credit.instanceId()).orElseThrow().state());
    }
    @Test void failureBetweenJournalAndReceiptRollsBackAllParticipants() throws Exception {
        var credit = credit(); var broken = new MariaMiningXpLedger(faultSource(false));
        assertThrows(SQLException.class, () -> broken.apply(credit, plan(account)));
        assertTrue(new MariaMiningJournal(source).find(credit.instanceId()).isEmpty());
        assertTrue(ledger.findReceipt(credit.creditId()).isEmpty());
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
        assertTrue(ledger.recoverOperation(credit, plan(account)).isEmpty());
        assertEquals(MariaMiningJournal.AttemptState.COMMIT_FAILED, new MariaMiningJournal(source).incident(credit.creditId()).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> ledger.apply(credit, plan(account)));
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
    }
    @Test void lostCommitResponseRecoversWithoutAnotherXpIncrement() throws Exception {
        var credit = credit(); var plan = plan(account);
        assertThrows(SQLException.class, () -> new MariaMiningXpLedger(faultSource(true)).apply(credit, plan));
        var recovered = new MariaMiningXpLedger(source).apply(credit, plan);
        assertEquals(new Progress(2, 50, 8), recovered.account().progress());
        assertEquals(1, recovered.account().revision());
    }
    @Test void asynchronousParticipantRetainsCommitWhenItemIsAbsentAndRecoversOnServerThread() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var owner = Thread.currentThread();
        try (var bukkit = mockStatic(Bukkit.class);
             var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var participant = new MiningXpParticipant(tasks, ledger, id -> {
                assertSame(owner, Thread.currentThread()); assertEquals(account.pickaxeId(), id); return null;
            });
            var result = participant.apply(credit(), plan(account));
            var callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            var outcome = result.get(5, TimeUnit.SECONDS);
            assertEquals(MiningXpItemProjection.Result.CONFLICT, outcome.projection());
            var recovered = participant.recover(account.pickaxeId(), 1);
            callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            assertEquals(outcome, recovered.get(5, TimeUnit.SECONDS).orElseThrow());
            assertEquals(1, ledger.findAccount(account.pickaxeId()).orElseThrow().revision());
        }
    }
    /** Fault injection around real MariaDB operations; no deployment data or schema is modified. */
    com.infinitygear.api.v1.MiningIncidentService.Evidence evidence(MiningCredit credit) {
        return new com.infinitygear.api.v1.MiningIncidentService.Evidence(credit.creditId(), credit.playerId(), credit.pickaxeId(),
                "Convict", UUID.randomUUID(), credit.worldId(), credit.x(), credit.y(), credit.z(), credit.originalBlockData(),
                "NATURAL", "trusted test producer returned true; final AIR; unchanged generation/placement/mutation", "PHYSICAL_RETURN",
                "TEST_COMPLETION", java.time.Instant.now(), Map.of("Paper", "26.2", "AxMines", "1.8.0", "InfinityGear", "test"), "test-config-1");
    }
    @Test void crashAfterPhysicalRemovalBeforeXpTransactionNeverReplays() throws Exception {
        var credit = credit(); var plan = plan(account); var journal = new MariaMiningJournal(source); var evidence = evidence(credit);
        assertTrue(journal.recordAttempt(evidence, credit.instanceId(), MariaMiningJournal.AttemptState.CONFIRMED));
        // Simulated process loss at the durable marker: the physical world is deliberately not queried.
        var restarted = new MariaMiningXpLedger(source);
        assertTrue(restarted.recoverOperation(credit, plan).isEmpty());
        assertThrows(IllegalStateException.class, () -> restarted.apply(credit, plan, evidence));
        assertEquals(account, restarted.findAccount(account.pickaxeId()).orElseThrow());
        assertTrue(journal.pendingNotifications(1000).stream().noneMatch(c -> c.creditId().equals(credit.creditId())));
        var incident = journal.incident(credit.creditId()).orElseThrow();
        assertEquals(evidence, incident.evidence()); assertEquals(MariaMiningJournal.AttemptState.COMMIT_FAILED, incident.state());
        assertNotNull(incident.recordedAt()); assertNotNull(incident.updatedAt());
    }
    @Test void uncertainDurableAttemptIsForensicOnlyAndBecomesAmbiguous() throws Exception {
        var credit = credit(); var journal = new MariaMiningJournal(source);
        journal.recordAttempt(evidence(credit), credit.instanceId(), MariaMiningJournal.AttemptState.ATTEMPTED);
        assertTrue(ledger.recoverOperation(credit, plan(account)).isEmpty());
        assertEquals(MariaMiningJournal.AttemptState.AMBIGUOUS, journal.incident(credit.creditId()).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> ledger.apply(credit, plan(account)));
        assertTrue(journal.incidents(null, 1000).stream().anyMatch(i -> i.evidence().operationId().equals(credit.creditId())));
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
    }
    @Test void crashBeforeAnyDurableRecordCannotInventIncidentOrReward() throws Exception {
        var credit = credit();
        assertTrue(ledger.recoverOperation(credit, plan(account)).isEmpty());
        assertTrue(new MariaMiningJournal(source).incident(credit.creditId()).isEmpty());
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
    }
    @Test void participantResolvesLostResponseByReceiptWithoutResubmission() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var credit = credit(); var plan = plan(account);
        try (var bukkit = mockStatic(Bukkit.class); var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var participant = new MiningXpParticipant(tasks, new MariaMiningXpLedger(faultSource(true)), id -> null);
            var result = participant.apply(credit, plan);
            var callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            assertEquals(1, result.get(5, TimeUnit.SECONDS).receipt().account().revision());
            assertEquals(1, ledger.findAccount(account.pickaxeId()).orElseThrow().revision());
            assertEquals(MariaMiningJournal.AttemptState.COMMITTED, new MariaMiningJournal(source).incident(credit.creditId()).orElseThrow().state());
        }
    }
    @Test void incidentPersistencePrecedesAlertsAndDuplicateCallsDoNotRealert() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var credit = credit(); var evidence = evidence(credit);
        var alerts = org.mockito.Mockito.mock(com.infinitygear.mining.MiningIncidentAlerts.class);
        Thread owner = Thread.currentThread();
        DataSource checked = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p,m,a) -> {
            assertNotSame(owner, Thread.currentThread(), "No JDBC on the server thread"); return invoke(m, source, a);
        });
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add)) {
            var recorder = new com.infinitygear.mining.MiningIncidentRecorder(tasks, new MariaMiningJournal(checked), alerts);
            var saved = recorder.recordVoided(evidence).toCompletableFuture();
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback);
            org.mockito.Mockito.verifyNoInteractions(alerts);
            assertEquals(evidence, new MariaMiningJournal(source).incident(credit.creditId()).orElseThrow().evidence());
            callback.run(); assertTrue(saved.get(5, TimeUnit.SECONDS));
            assertTrue(recorder.recordVoided(evidence).toCompletableFuture().get(5, TimeUnit.SECONDS));
            org.mockito.Mockito.verify(alerts, org.mockito.Mockito.times(1)).persisted(org.mockito.ArgumentMatchers.any());
        }
    }
    @Test void failedIncidentPersistenceNeverSchedulesStaffAlert() throws Exception {
        var alerts = org.mockito.Mockito.mock(com.infinitygear.mining.MiningIncidentAlerts.class);
        var unavailable = org.mockito.Mockito.mock(DataSource.class);
        org.mockito.Mockito.when(unavailable.getConnection()).thenThrow(new SQLException("database unavailable"));
        var callbacks = new LinkedBlockingQueue<Runnable>();
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add)) {
            var recorder = new com.infinitygear.mining.MiningIncidentRecorder(tasks, new MariaMiningJournal(unavailable), alerts);
            assertThrows(ExecutionException.class, () -> recorder.recordVoided(evidence(credit())).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(callbacks.isEmpty()); org.mockito.Mockito.verifyNoInteractions(alerts);
        }
    }
    @Test void contradictoryUnconfirmedReportDuringXpTransactionRollsBackReward() throws Exception {
        var credit = credit(); var evidence = evidence(credit); var journal = new MariaMiningJournal(source); var fired = new AtomicBoolean();
        DataSource intervening = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p,m,a) -> {
            Object value = invoke(m, source, a);
            if (!m.getName().equals("getConnection")) return value;
            Connection connection = (Connection) value;
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (cp,cm,ca) -> {
                if (cm.getName().equals("prepareStatement") && ca[0].toString().startsWith("INSERT INTO infinitygear_mining_xp_receipts") && fired.compareAndSet(false,true))
                    journal.recordVoided(evidence);
                return invoke(cm, connection, ca);
            });
        });
        assertThrows(IllegalStateException.class, () -> new MariaMiningXpLedger(intervening).apply(credit, plan(account), evidence));
        assertTrue(fired.get()); assertTrue(ledger.findReceipt(credit.creditId()).isEmpty());
        assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
        assertEquals(MariaMiningJournal.AttemptState.AMBIGUOUS, journal.incident(credit.creditId()).orElseThrow().state());
    }
    @Test void failedConfirmedCommitPersistsIncidentAndNeverProjects() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>(); var credit = credit(); var evidence = evidence(credit);
        var alerts = org.mockito.Mockito.mock(com.infinitygear.mining.MiningIncidentAlerts.class);
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), callbacks::add)) {
            var recorder = new com.infinitygear.mining.MiningIncidentRecorder(tasks, new MariaMiningJournal(source), alerts);
            var participant = new MiningXpParticipant(tasks, new MariaMiningXpLedger(faultSource(false)), id -> { fail("No projection for voided commit"); return null; }, recorder);
            var result = participant.complete(credit, plan(account), new com.infinitygear.mining.MiningCompletion(true,true,true,true,true,true,true,false), evidence);
            var callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(MariaMiningJournal.AttemptState.COMMIT_FAILED, new MariaMiningJournal(source).incident(credit.creditId()).orElseThrow().state());
            assertEquals(account, ledger.findAccount(account.pickaxeId()).orElseThrow());
            assertThrows(IllegalStateException.class, () -> ledger.apply(credit, plan(account), evidence));
            org.mockito.Mockito.verify(alerts).persisted(org.mockito.ArgumentMatchers.any());
        }
    }
    DataSource faultSource(boolean loseCommitResponse) {
        var fired = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object result = invoke(method, source, args);
            if (!method.getName().equals("getConnection")) return result;
            Connection connection = (Connection) result;
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                if (!loseCommitResponse && m.getName().equals("prepareStatement") && a[0].toString().startsWith("INSERT INTO infinitygear_mining_xp_receipts"))
                    throw new SQLException("Injected receipt write failure");
                Object value = invoke(m, connection, a);
                if (loseCommitResponse && m.getName().equals("commit") && fired.compareAndSet(false, true))
                    throw new SQLException("Injected lost commit response");
                return value;
            });
        });
    }
    static Object invoke(Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
