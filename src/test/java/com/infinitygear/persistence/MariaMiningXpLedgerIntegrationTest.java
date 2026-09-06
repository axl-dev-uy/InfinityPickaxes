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
        assertEquals(1, ledger.apply(credit, plan(account)).account().revision());
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
