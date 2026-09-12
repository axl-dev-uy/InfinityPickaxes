package com.infinitygear.mining;

import com.infinitygear.api.v1.*;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailClosedMiningTest {
    MiningCredit credit() { return new MiningCredit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "pickaxe", UUID.randomUUID(), 1, 2, 3, "minecraft:stone", MiningCredit.Source.NORMAL, UUID.randomUUID().toString(), true, true); }
    MiningIncidentService.Evidence evidence(MiningCredit c) { return new MiningIncidentService.Evidence(c.creditId(), c.playerId(), c.pickaxeId(),
            "Convict", UUID.fromString(c.generation()), c.worldId(), c.x(), c.y(), c.z(), c.originalBlockData(), "UNKNOWN", "test producer",
            "PHYSICAL_RETURN", "UNCONFIRMED", Instant.now(), Map.of("Paper", "26.2"), "config-1"); }
    MariaMiningJournal.Incident incident() { return new MariaMiningJournal.Incident(evidence(credit()), MariaMiningJournal.AttemptState.AMBIGUOUS,
            "UNCONFIRMED", Instant.now(), Instant.now()); }

    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7})
    void everyMissingCompletionFactVoidsWithoutXpOrProjection(int missing) throws Exception {
        var ledger = mock(MariaMiningXpLedger.class); var recorder = mock(MiningIncidentService.class);
        when(recorder.recordVoided(any())).thenReturn(CompletableFuture.completedFuture(true));
        boolean[] facts = {true,true,true,true,true,true,true,false}; facts[missing] = !facts[missing];
        var completion = new MiningCompletion(facts[0],facts[1],facts[2],facts[3],facts[4],facts[5],facts[6],facts[7]);
        var c = credit(); var e = evidence(c);
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var participant = new MiningXpParticipant(tasks, ledger, id -> { fail("No projection for unconfirmed removal"); return null; }, recorder);
            assertTrue(participant.complete(c, null, completion, e).get(5, TimeUnit.SECONDS).isEmpty());
            verify(recorder).recordVoided(e); verifyNoInteractions(ledger);
        }
    }
    @Test void auditFailureCannotTurnUnconfirmedIntoReward() throws Exception {
        var ledger = mock(MariaMiningXpLedger.class); var recorder = mock(MiningIncidentService.class);
        when(recorder.recordVoided(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        try (var tasks = new IntegrationTasks(Executors.newSingleThreadExecutor(), Runnable::run)) {
            var c = credit(); var participant = new MiningXpParticipant(tasks, ledger, id -> null, recorder);
            assertTrue(participant.complete(c, null, new MiningCompletion(false,true,true,true,true,true,true,false), evidence(c)).get().isEmpty());
            verifyNoInteractions(ledger);
        }
    }
    @Test void alertsHonorConfiguredPermissionAndAggregateWithinRateWindow() {
        var staff = mock(Player.class); var other = mock(Player.class); var now = new AtomicLong(1000);
        when(staff.hasPermission("custom.staff.alert")).thenReturn(true);
        var config = new AtomicReference<>(new MiningIncidentAlerts.Settings(true, "custom.staff.alert", 60000, "{count}:{operation}"));
        var alerts = new MiningIncidentAlerts(config::get, () -> List.of(staff,other), now::get);
        var first = incident(); alerts.persisted(first);
        verify(staff).sendMessage("1:" + first.evidence().operationId());
        alerts.persisted(incident()); alerts.persisted(incident());
        now.addAndGet(60000); var last = incident(); alerts.persisted(last);
        verify(staff).sendMessage("3:" + last.evidence().operationId());
        verify(other, never()).sendMessage(anyString());
        verify(staff, never()).hasPermission("noxwardarchives.alerts.mining");
        config.set(new MiningIncidentAlerts.Settings(false, "custom.staff.alert", 60000, "{count}"));
        now.addAndGet(60000); alerts.persisted(incident()); verify(staff, times(2)).sendMessage(anyString());
    }
    @Test void offlineStaffDoNotDiscardAggregateAndDeliveryFailureIsIsolated() {
        var now = new AtomicLong(0); var online = new ArrayList<Player>(); var staff = mock(Player.class);
        var alerts = new MiningIncidentAlerts(() -> new MiningIncidentAlerts.Settings(true, "staff", 1000, "{count}"), () -> online, now::get);
        alerts.persisted(incident());
        online.add(staff); when(staff.hasPermission("staff")).thenReturn(true); now.set(1000);
        alerts.persisted(incident()); verify(staff).sendMessage("2");
        doThrow(new IllegalStateException("disconnected")).when(staff).sendMessage(anyString());
        now.set(2000); assertDoesNotThrow(() -> alerts.persisted(incident()));
    }
    @Test void onlyDurableTerminalIncidentStatesAreAlertableAndDefaultsAreOptIn() {
        var defaults = MiningIncidentAlerts.Settings.read(new YamlConfiguration());
        assertFalse(defaults.enabled()); assertEquals("noxwardarchives.alerts.mining", defaults.permission());
        var staff = mock(Player.class); var alerts = new MiningIncidentAlerts(() -> new MiningIncidentAlerts.Settings(true,"staff",1000,"test"), () -> List.of(staff), () -> 1);
        for (var state : List.of(MariaMiningJournal.AttemptState.ATTEMPTED, MariaMiningJournal.AttemptState.CONFIRMED, MariaMiningJournal.AttemptState.COMMITTED))
            alerts.persisted(new MariaMiningJournal.Incident(evidence(credit()), state, "test", Instant.now(), Instant.now()));
        verifyNoInteractions(staff);
    }
    @Test void forensicCodecPreservesContextAndRejectsCorruption() {
        var evidence = evidence(credit()); byte[] bytes = MiningEvidenceCodec.encode(evidence);
        assertEquals(evidence, MiningEvidenceCodec.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> MiningEvidenceCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> MiningEvidenceCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        bytes[3] = 2; assertThrows(IllegalArgumentException.class, () -> MiningEvidenceCodec.decode(bytes));
    }
}
